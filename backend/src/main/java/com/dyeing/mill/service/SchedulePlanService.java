package com.dyeing.mill.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.SchedulePlan;
import com.dyeing.mill.domain.WashRuleVersion;
import com.dyeing.mill.dto.ScheduleGenerateRequest;
import com.dyeing.mill.engine.ScheduleEngine;
import com.dyeing.mill.engine.ScheduleInput;
import com.dyeing.mill.engine.ScheduleOutput;
import com.dyeing.mill.repository.ColorFamilyRepository;
import com.dyeing.mill.repository.MaintenanceWindowRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;
import com.dyeing.mill.repository.SchedulePlanAssignmentRepository;
import com.dyeing.mill.repository.SchedulePlanRepository;
import com.dyeing.mill.repository.WashRuleRepository;
import com.dyeing.mill.repository.WashRuleVersionRepository;

/**
 * 换色排程方案服务：
 *  - 生成在后台线程执行（批量重算不阻塞页面），并对“相同输入指纹”的并发请求做合并（去重）；
 *  - 方案只存排程结果与输入快照，绝不回写布车/染缸，也不开执行单；
 *  - 复算（replay）按方案保存的规则版本 + 输入快照重跑引擎，得到当时口径的解释。
 */
@Service
public class SchedulePlanService {

    private final SchedulePlanRepository planRepository;
    private final SchedulePlanAssignmentRepository assignmentRepository;
    private final WashRuleRepository ruleRepository;
    private final WashRuleVersionRepository ruleVersionRepository;
    private final WashRuleService washRuleService;
    private final ColorFamilyRepository familyRepository;
    private final MaintenanceWindowRepository maintenanceRepository;
    private final ScheduleLockRepository lockRepository;
    private final ScheduleInputAssembler assembler;
    private final ScheduleTxBean txBean;

    /** 正在执行的 live 重算作业：scopeKey -> job */
    private final Map<String, Job> activeJobs = new ConcurrentHashMap<>();
    /** 已排队的最新 live 重算作业：连续改交期/锁定时，旧排队作业会被最新请求替换并合并 */
    private final Map<String, Job> pendingJobs = new ConcurrentHashMap<>();
    private final java.util.LinkedList<Job> pendingOrder = new java.util.LinkedList<>();
    /** 兼容复算/测试按 jobId 检索的作业索引 */
    private final Map<Long, Job> jobsById = new ConcurrentHashMap<>();
    /** 已完成作业短期保留，防止“计算快于首次轮询”时页面查不到结果 */
    private final Map<Long, Job> finishedJobs = new ConcurrentHashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(1);
    private boolean workerBusy;

    /** 单线程重算池：批量重算串行执行，避免把页面/数据库拖到长时间无响应 */
    private final ExecutorService recomputePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schedule-recompute");
        t.setDaemon(true);
        return t;
    });

    public SchedulePlanService(SchedulePlanRepository planRepository,
                               SchedulePlanAssignmentRepository assignmentRepository,
                               WashRuleRepository ruleRepository,
                               WashRuleVersionRepository ruleVersionRepository,
                               WashRuleService washRuleService,
                               ColorFamilyRepository familyRepository,
                               MaintenanceWindowRepository maintenanceRepository,
                               ScheduleLockRepository lockRepository,
                               ScheduleInputAssembler assembler,
                               ScheduleTxBean txBean) {
        this.planRepository = planRepository;
        this.assignmentRepository = assignmentRepository;
        this.ruleRepository = ruleRepository;
        this.ruleVersionRepository = ruleVersionRepository;
        this.washRuleService = washRuleService;
        this.familyRepository = familyRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.lockRepository = lockRepository;
        this.assembler = assembler;
        this.txBean = txBean;
    }

    // ----------------------------------------------------------------
    // 异步重算作业
    // ----------------------------------------------------------------

    /**
     * 发起重算。同 scope 的并发请求合并；当前作业执行期间再次修改交期/锁定，只保留一个排队中的
     * 最新作业，前一个排队作业标记 SUPERSEDED。单线程池串行跑，最终 planId 对应最新一次提交。
     */
    public synchronized JobSubmit submitGenerate(ScheduleGenerateRequest request) {
        int horizonHours = request.getHorizonHours() == null || request.getHorizonHours() <= 0
                ? 120 : Math.min(request.getHorizonHours(), 24 * 30);
        LocalDateTime anchor = request.getAnchorTime() == null || request.getAnchorTime().isBlank()
                ? LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                : parseTime(request.getAnchorTime());
        String scopeKey = "live|" + horizonHours + "|" + anchor;
        Long revision = request.getClientRevision();
        Job active = activeJobs.get(scopeKey);
        if (active != null && (revision == null
                || active.clientRevision == null || revision <= active.clientRevision)) {
            return submit(active, true);
        }
        Job pending = pendingJobs.get(scopeKey);
        if (pending != null) {
            if (revision == null || revision.equals(pending.clientRevision)
                    || (revision != null && pending.clientRevision != null && revision < pending.clientRevision)) {
                pending.planName = request.getPlanName();
                pending.createdBy = request.getCreatedBy();
                return submit(pending, true);
            }
            pending.clientRevision = revision;
            pending.planName = request.getPlanName();
            pending.createdBy = request.getCreatedBy();
            pending.submitTime = LocalDateTime.now();
            return submit(pending, false);
        }

        if (active != null) {
            Job queued = newJob(scopeKey, request.getPlanName(), request.getCreatedBy());
            queued.clientRevision = revision;
            queued.status = "PENDING";
            pendingJobs.put(scopeKey, queued);
            pendingOrder.add(queued);
            return submit(queued, false);
        }

        Job job = newJob(scopeKey, request.getPlanName(), request.getCreatedBy());
        job.clientRevision = revision;
        if (workerBusy) {
            pendingJobs.put(scopeKey, job);
            pendingOrder.add(job);
            jobsById.put(job.jobId, job);
            job.status = "PENDING";
        } else {
            startJob(job, horizonHours, anchor);
        }
        return submit(job, false);
    }

    private synchronized JobSubmit submit(Job job, boolean merged) {
        JobSubmit result = new JobSubmit();
        result.jobId = job.jobId;
        result.status = job.status;
        result.merged = merged;
        return result;
    }

    public synchronized Job getJob(long jobId) {
        Job job = jobsById.get(jobId);
        if (job != null && "SUPERSEDED".equals(job.status) && job.supersededByJobId != null) {
            Job next = getJob(job.supersededByJobId);
            if (next != null) {
                return next;
            }
        }
        if (job != null) {
            return job;
        }
        Job finished = finishedJobs.get(jobId);
        if (finished != null && finished.finishTime != null
                && finished.finishTime.isBefore(LocalDateTime.now().minusMinutes(10))) {
            finishedJobs.remove(jobId);
            return null;
        }
        return finished;
    }

    private Job newJob(String scopeKey, String planName, String createdBy) {
        Job j = new Job();
        j.jobId = jobSeq.getAndIncrement();
        j.scopeKey = scopeKey;
        j.planName = planName;
        j.createdBy = createdBy;
        j.status = "PENDING";
        j.submitTime = LocalDateTime.now();
        jobsById.put(j.jobId, j);
        return j;
    }

    private synchronized void startJob(Job job, int horizonHours, LocalDateTime anchor) {
        activeJobs.put(job.scopeKey, job);
        job.status = "RUNNING";
        workerBusy = true;
        recomputePool.submit(() -> runGenerate(job, horizonHours, anchor));
    }

    /** 当前作业结束后由后台线程调用：串行启动排队中最新、且未被同 scope 新请求合并的作业。 */
    private synchronized void finishCurrent(Job finished) {
        activeJobs.remove(finished.scopeKey);
        while (!pendingOrder.isEmpty()) {
            Job queued = pendingOrder.removeFirst();
            Job latest = pendingJobs.remove(queued.scopeKey);
            if (latest != queued) {
                queued.status = "SUPERSEDED";
                jobsById.remove(queued.jobId);
                continue;
            }
            Job effective = queued;
            queued.status = "RUNNING";
            activeJobs.put(effective.scopeKey, effective);
            JobParams params = parseScope(effective.scopeKey);
            recomputePool.submit(() -> runGenerate(effective, params.horizonHours(), params.anchor()));
            return;
        }
        workerBusy = false;
    }

    private JobParams parseScope(String scopeKey) {
        String[] parts = scopeKey.split("\\|", 3);
        return new JobParams(Integer.parseInt(parts[1]), LocalDateTime.parse(parts[2]));
    }

    private record JobParams(int horizonHours, LocalDateTime anchor) {
    }

    /** 后台线程执行：只读取数 → 引擎计算 → 独立事务落库（均经跨 Bean 调用，事务生效）。 */
    void runGenerate(Job job, int horizonHours, LocalDateTime anchor) {
        try {
            GenerateBundle bundle = txBean.loadLiveBundle(horizonHours, anchor);
            ScheduleEngine engine = new ScheduleEngine(bundle.input.getFamilies(), bundle.input.getWashCells());
            ScheduleOutput output = engine.solve(bundle.input);
            SchedulePlan plan = txBean.persistPlan(job.planName, job.createdBy, bundle, output, "NOW");
            job.planId = plan.getId();
            job.planCode = plan.getPlanCode();
            job.status = "DONE";
        } catch (Exception e) {
            job.status = "FAILED";
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            job.finishTime = LocalDateTime.now();
            jobsById.remove(job.jobId);
            finishedJobs.put(job.jobId, job);
            finishCurrent(job);
        }
    }

    /** 事务落库统一委托 ScheduleTxBean（后台线程内自调用不会开启事务）。 */
    public SchedulePlan persistPlan(String planName, String createdBy, GenerateBundle bundle,
                                    ScheduleOutput output, String mode) {
        return txBean.persistPlan(planName, createdBy, bundle, output, mode);
    }

    // ----------------------------------------------------------------
    // 方案列表 / 详情 / 留存（选定参考）
    // ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SchedulePlan> listPlans() {
        return planRepository.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public SchedulePlan detail(Long id) {
        SchedulePlan plan = planRepository.findById(id)
                .orElseThrow(() -> new BizException("排程方案不存在：id=" + id));
        if (plan.getAssignments().isEmpty()) {
            plan.getAssignments().addAll(assignmentRepository.findByPlanId(id));
        }
        return plan;
    }

    /** 方案完整视图：时间轴、染缸负载、清洗占时、解释、口径版本都从这里取。 */
    @Transactional(readOnly = true)
    public Map<String, Object> planView(Long id) {
        SchedulePlan plan = detail(id);
        ScheduleInput replayedInput = assembler.fromSnapshot(plan.getInputSnapshotJson(),
                washRuleService.getVersion(plan.getRuleVersionId()));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("plan", planSummary(plan));
        data.put("assignments", plan.getAssignments());
        // 时间轴按方案保存时的快照展示，避免当前维护/锁定变化后旧方案看起来被改写。
        data.put("maintenance", toMaintenanceViews(replayedInput));
        data.put("locks", toLockViews(replayedInput));
        data.put("vats", toVatViews(replayedInput));
        data.put("families", toFamilyViews(replayedInput));
        data.put("warnings", plan.getAssignments().stream()
                .filter(a -> a.getLocked() != null && a.getLocked() == 1 && a.getWashStartTime() == null
                        && a.getReasonText() != null && a.getReasonText().contains("没有连续"))
                .map(a -> a.getReasonText())
                .toList());
        data.put("ruleVersion", washRuleService.versionView(washRuleService.getVersion(plan.getRuleVersionId())));
        return data;
    }

    private List<Map<String, Object>> toFamilyViews(ScheduleInput input) {
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ScheduleInput.EFamily f : input.getFamilies()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("familyCode", f.getCode());
            m.put("familyName", f.getName());
            m.put("depthLevel", f.getDepthLevel());
            m.put("dyeClass", f.getDyeClass());
            m.put("colorHex", f.getColorHex());
            views.add(m);
        }
        return views;
    }

    private List<Map<String, Object>> toMaintenanceViews(ScheduleInput input) {
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ScheduleInput.EBlock b : input.getMaintenance()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("vatId", b.getVatId());
            m.put("vatCode", b.getVatCode());
            m.put("startTime", b.getStart());
            m.put("endTime", b.getEnd());
            m.put("reason", b.getReason() == null ? "维护" : b.getReason().replaceFirst("^维护：?", ""));
            views.add(m);
        }
        return views;
    }

    private List<Map<String, Object>> toLockViews(ScheduleInput input) {
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ScheduleInput.EBlock b : input.getLocks()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("vatId", b.getVatId());
            m.put("vatCode", b.getVatCode());
            m.put("cartId", b.getCartId());
            m.put("cartCode", b.getCartCode());
            m.put("startTime", b.getStart());
            m.put("endTime", b.getEnd());
            m.put("lockReason", b.getReason());
            views.add(m);
        }
        return views;
    }

    private List<Map<String, Object>> toVatViews(ScheduleInput input) {
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ScheduleInput.EVat v : input.getVats()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("vatCode", v.getCode());
            m.put("machineType", v.getMachineType());
            m.put("capacityKg", v.getCapacityKg());
            m.put("workshop", v.getWorkshop());
            views.add(m);
        }
        return views;
    }

    public Map<String, Object> planSummary(SchedulePlan plan) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", plan.getId());
        m.put("planCode", plan.getPlanCode());
        m.put("planName", plan.getPlanName());
        m.put("status", plan.getStatus());
        m.put("ruleVersionId", plan.getRuleVersionId());
        m.put("ruleVersionNo", plan.getRuleVersionNo());
        m.put("anchorMode", plan.getAnchorMode());
        m.put("horizonStartTime", plan.getHorizonStartTime());
        m.put("horizonEndTime", plan.getHorizonEndTime());
        m.put("totalCarts", plan.getTotalCarts());
        m.put("scheduledCarts", plan.getScheduledCarts());
        m.put("excludedCarts", plan.getExcludedCarts());
        m.put("lateCarts", plan.getLateCarts());
        m.put("totalWashMinutes", plan.getTotalWashMinutes());
        m.put("totalWashCost", plan.getTotalWashCost());
        m.put("totalIdleMinutes", plan.getTotalIdleMinutes());
        m.put("totalLateMinutes", plan.getTotalLateMinutes());
        m.put("createdBy", plan.getCreatedBy());
        m.put("createTime", plan.getCreateTime());
        m.put("referenceTime", plan.getReferenceTime());
        return m;
    }

    /**
     * 选定为车间参考：同表仅一个 REFERENCE（生成列唯一索引兜底），旧参考自动降为 DRAFT。
     * 只是标注，不回写布车或开缸执行单。
     */
    @Transactional
    public SchedulePlan markReference(Long id) {
        SchedulePlan plan = detail(id);
        Optional<SchedulePlan> current = planRepository.findReference();
        current.ifPresent(p -> {
            p.setStatus("DRAFT");
            p.setReferenceTime(null);
            planRepository.save(p);
        });
        plan.setStatus("REFERENCE");
        plan.setReferenceTime(LocalDateTime.now());
        return planRepository.save(plan);
    }

    @Transactional
    public SchedulePlan rename(Long id, String name) {
        SchedulePlan plan = detail(id);
        if (name == null || name.isBlank()) {
            throw new BizException("方案名称不能为空");
        }
        plan.setPlanName(name.trim().length() > 64 ? name.trim().substring(0, 64) : name.trim());
        return planRepository.save(plan);
    }

    // ----------------------------------------------------------------
    // 版本兼容复算：方案当时的规则版本 + 输入快照重跑，得到原口径解释
    // ----------------------------------------------------------------

    @Transactional
    public SchedulePlan replay(Long id, String createdBy) {
        SchedulePlan plan = detail(id);
        WashRuleVersion rule = ruleVersionRepository.findById(plan.getRuleVersionId())
                .orElseThrow(() -> new BizException("方案使用的规则版本已不存在，无法复算"));
        rule.setRules(ruleRepository.findByVersionIdOrderByIdAsc(rule.getId()));
        ScheduleInput input = assembler.fromSnapshot(plan.getInputSnapshotJson(), rule);
        ScheduleEngine engine = new ScheduleEngine(input.getFamilies(), input.getWashCells());
        ScheduleOutput output = engine.solve(input);

        GenerateBundle bundle = new GenerateBundle();
        bundle.input = input;
        bundle.rule = rule;
        return persistPlan(plan.getPlanName() + "（复算 V" + rule.getVersionNo() + "）",
                createdBy, bundle, output, "REPLAY");
    }

    private LocalDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式有误，应为 yyyy-MM-dd HH:mm");
        }
    }

    // ---- 内部传输结构 ----
    public static class GenerateBundle {
        public ScheduleInput input;
        public WashRuleVersion rule;
    }

    /** 重算作业（页面轮询用） */
    public static class Job {
        public long jobId;
        public String scopeKey;
        public String planName;
        public String createdBy;
        public volatile String status;
        public volatile Long planId;
        public volatile Long clientRevision;
        public volatile String planCode;
        public volatile String error;
        public volatile Long supersededByJobId;
        public volatile LocalDateTime submitTime;
        public volatile LocalDateTime finishTime;
    }

    public static class JobSubmit {
        public long jobId;
        public String status;
        /** 是否与进行中的作业合并（连续修改只重算最新一次） */
        public boolean merged;
    }
}
