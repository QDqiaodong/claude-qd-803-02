package com.dyeing.mill.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dyeing.mill.engine.ScheduleInput.EBlock;
import com.dyeing.mill.engine.ScheduleInput.ECart;
import com.dyeing.mill.engine.ScheduleInput.EFabricMachine;
import com.dyeing.mill.engine.ScheduleInput.EFamily;
import com.dyeing.mill.engine.ScheduleInput.EVat;
import com.dyeing.mill.engine.ScheduleInput.EWashCell;
import com.dyeing.mill.engine.ScheduleOutput.ECandidate;
import com.dyeing.mill.engine.ScheduleOutput.EResult;
import com.dyeing.mill.engine.WashMatrix.Lookup;

/**
 * 换色排程引擎（纯函数式、无副作用、可重放）。
 *
 * 约束（硬约束，违反即不能排）：
 *  1. 机型适配：布种只允许排到 fabric_machine 配置过的机型；
 *  2. 容量：折算布重 ≤ 缸容量；
 *  3. 时段不重叠：染色/在制/锁定/维护段互不重叠；
 *  4. 清洗接续：入缸前必须留足「上一缸色族 → 本车色族」矩阵规定的清洗时长；
 *  5. 视窗：anchor ~ horizonEnd 内放不下则排除（给出具体卡点）；
 *  6. 人工锁定段原样保留，重算只移动未锁定区段。
 *
 * 目标（软约束，评分排序）：先保交期（急单权重更高），再少逾期，再少空档，
 * 再省清洗成本/时长 —— 排序而非“只按颜色或交期排序”，所以不会为了省清洗长期压急单。
 */
public class ScheduleEngine {

    /** 评分权重（全部折算成“分钟等价量”） */
    private static final long W_LATE_RUSH = 60;      // 急单逾期 1 分钟
    private static final long W_LATE_NORMAL = 12;    // 普通单逾期 1 分钟
    private static final long W_IDLE = 1;            // 空档 1 分钟
    private static final long W_WASH_MIN = 1;        // 清洗 1 分钟
    private static final long W_WASH_COST = 2;       // 清洗 1 元 ≈ 2 分钟
    private static final long W_NON_PREFERRED = 90;  // 非首选机型固定代价
    private static final BigDecimal METERS_TO_KG = new BigDecimal("0.18");

    private final WashMatrix matrix;

    public ScheduleEngine(List<EFamily> families, List<EWashCell> cells) {
        this.matrix = new WashMatrix(families, cells);
    }

    public ScheduleOutput solve(ScheduleInput in) {
        ScheduleOutput out = new ScheduleOutput();

        // 机型适配表
        Map<String, List<String>> machineByFabric = new HashMap<>();
        Map<String, Boolean> preferredByPair = new HashMap<>();
        for (EFabricMachine fm : in.getFabricMachines()) {
            machineByFabric.computeIfAbsent(fm.getFabricType(), k -> new ArrayList<>());
            List<String> types = machineByFabric.get(fm.getFabricType());
            if (!types.contains(fm.getMachineType())) {
                types.add(fm.getMachineType());
            }
            preferredByPair.put(fm.getFabricType() + "|" + fm.getMachineType(), fm.isPreferred());
        }
        Map<Long, EVat> vatById = new HashMap<>();
        for (EVat v : in.getVats()) {
            vatById.put(v.getId(), v);
        }

        // 每缸时间轴：固定段（在制/锁定/维护）+ 后续动态排入段，统一按 start 排序
        Map<Long, List<TimelineEntry>> timelines = new HashMap<>();
        for (EVat v : in.getVats()) {
            timelines.put(v.getId(), new ArrayList<>());
        }
        for (EBlock b : in.getMaintenance()) {
            addEntry(timelines, b.getVatId(), TimelineEntry.block(b));
        }
        for (EBlock b : in.getLocks()) {
            addEntry(timelines, b.getVatId(), TimelineEntry.block(b));
        }
        for (EBlock b : in.getRunningBlocks()) {
            addEntry(timelines, b.getVatId(), TimelineEntry.block(b));
        }

        // 固定段先入输出（在制 + 锁定绑布车的段）。锁定前清洗在所有动态排程完成后统一回填，
        // 这样才能看到锁定前最后一辆自动排入布车的色族，避免动态车挤占承诺清洗口。
        for (EBlock b : in.getRunningBlocks()) {
            out.getResults().add(fixedResult(b, in));
            out.setFixedCount(out.getFixedCount() + 1);
        }
        for (EBlock b : in.getLocks()) {
            if (b.getCartId() != null) {
                out.getResults().add(lockedResult(b, in));
                out.setFixedCount(out.getFixedCount() + 1);
            }
        }
        // 被锁定段绑走的布车不再参与自动排（锁定段已固定入输出）
        java.util.Set<Long> lockedCartIds = in.getLocks().stream()
                .map(EBlock::getCartId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        // ---- 硬校验：资料/机型/容量，不过的直接排除，永远不进入贪心 ----
        List<ECart> schedulable = new ArrayList<>();
        Map<Long, List<String>> exclusionReasons = new HashMap<>();
        for (ECart cart : in.getCarts()) {
            // 只对待排产（含返修）且未被锁定段绑走的布车排程；在制/已完成车由固定段或既有流程负责
            if (!"PENDING".equals(cart.getStatus()) && !"REWORK".equals(cart.getStatus())) {
                continue;
            }
            if (lockedCartIds.contains(cart.getId())) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (cart.getFamilyId() == null) {
                reasons.add("色号 " + cart.getColorCode() + " 未配置颜色族映射，无法判定清洗切换规则");
            }
            if (!cart.isRecipeReady()) {
                reasons.add("配方资料不完整：" + (cart.getRecipeIssue() != null ? cart.getRecipeIssue()
                        : "布种 " + cart.getFabricType() + "、色号 " + cart.getColorCode() + " 没有已发布配方"));
            }
            if (!machineByFabric.containsKey(cart.getFabricType())) {
                reasons.add("机型不适配：布种 " + cart.getFabricType() + " 未配置任何可用机型，全部染缸都不能做");
            }
            BigDecimal weight = cart.getWeightKg() != null ? cart.getWeightKg()
                    : nvl(cart.getMeters()).multiply(METERS_TO_KG);
            cart.setWeightKg(weight);
            boolean anyCapacity = in.getVats().stream()
                    .anyMatch(v -> machineByFabric.getOrDefault(cart.getFabricType(), List.of())
                            .contains(v.getMachineType())
                            && v.getCapacityKg() != null && v.getCapacityKg().compareTo(weight) >= 0);
            if (machineByFabric.containsKey(cart.getFabricType()) && !anyCapacity) {
                reasons.add("容量不足：布车折算布重 " + weight.stripTrailingZeros().toPlainString()
                        + "kg，适配机型的染缸容量都不够（最大也装不下）");
            }
            if (!reasons.isEmpty()) {
                exclusionReasons.put(cart.getId(), reasons);
            } else {
                schedulable.add(cart);
            }
        }

        // ---- 布车优先级：急单 → 交期（含已逾期小时数）→ 米数大的先排 ----
        LocalDateTime anchor = in.getAnchor();
        schedulable.sort(Comparator
                .comparing((ECart c) -> c.isRush() ? 0 : 1)
                .thenComparing(c -> c.getDueTime() == null ? LocalDateTime.MAX : c.getDueTime())
                .thenComparing(ECart::getId));

        // ---- 逐车为所有适配缸找最早可行落点，按综合评分选缸 ----
        for (ECart cart : schedulable) {
            List<ECandidate> feasibleCandidates = new ArrayList<>();
            List<Placement> feasiblePlacements = new ArrayList<>();
            List<ECandidate> candidateLog = new ArrayList<>();
            List<String> horizonBlockers = new ArrayList<>();

            for (EVat vat : in.getVats()) {
                List<String> types = machineByFabric.get(cart.getFabricType());
                if (!types.contains(vat.getMachineType())) {
                    continue; // 机型不适配的缸不参与（车级已确认至少有适配缸）
                }
                if (vat.getCapacityKg() == null || vat.getCapacityKg().compareTo(cart.getWeightKg()) < 0) {
                    ECandidate rej = new ECandidate();
                    rej.setVatId(vat.getId());
                    rej.setVatCode(vat.getCode());
                    rej.setFeasible(false);
                    rej.setRejectCode("CAPACITY");
                    rej.setRejectText("容量 " + vat.getCapacityKg() + "kg < 布重 "
                            + cart.getWeightKg().stripTrailingZeros().toPlainString() + "kg");
                    candidateLog.add(rej);
                    continue;
                }

                Placement p = earliestPlacement(timelines.get(vat.getId()), cart, vat, anchor, in.getHorizonEnd());
                ECandidate cand = new ECandidate();
                cand.setVatId(vat.getId());
                cand.setVatCode(vat.getCode());
                cand.setWashMin(p.washMin);
                cand.setWashCost(p.washCost);
                cand.setSwitchType(p.switchType);
                if (p.feasible) {
                    cand.setFeasible(true);
                    cand.setEarliestStart(p.start);
                    long late = lateMinutes(p.start.plusMinutes(cart.getProcessMinutes()), cart.getDueTime());
                    long idle = idleBefore(anchor, timelines.get(vat.getId()), p);
                    long score = (cart.isRush() ? W_LATE_RUSH : W_LATE_NORMAL) * late
                            + W_IDLE * idle
                            + W_WASH_MIN * p.washMin
                            + W_WASH_COST * (p.washCost == null ? 0 : p.washCost.longValue())
                            + (Boolean.TRUE.equals(preferredByPair.get(cart.getFabricType() + "|" + vat.getMachineType()))
                            ? 0 : W_NON_PREFERRED);
                    cand.setScore(score);
                    p.lateMinutes = (int) late;
                    p.idleMinutes = (int) idle;
                    feasibleCandidates.add(cand);
                    feasiblePlacements.add(p);
                } else {
                    cand.setFeasible(false);
                    cand.setRejectCode(p.rejectCode);
                    cand.setRejectText(p.rejectText);
                    candidateLog.add(cand);
                    if ("HORIZON".equals(p.rejectCode)) {
                        horizonBlockers.add(vat.getCode() + "：" + p.rejectText);
                    }
                }
            }

            if (!feasibleCandidates.isEmpty()) {
                // 排序候选并同步对应的落点（落点不进序列化输出，仅内部关联）
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < feasibleCandidates.size(); i++) order.add(i);
                order.sort(Comparator.comparing((Integer i) -> feasibleCandidates.get(i).getScore())
                        .thenComparing(i -> feasibleCandidates.get(i).getEarliestStart()));
                ECandidate chosen = feasibleCandidates.get(order.get(0));
                Placement chosenPlacement = feasiblePlacements.get(order.get(0));
                EVat chosenVat = in.getVats().stream().filter(v -> v.getId().equals(chosen.getVatId())).findFirst().orElseThrow();
                EResult best = toResult(cart, chosenVat, chosenPlacement);
                best.getCandidates().add(chosen);
                for (int k = 1; k < order.size(); k++) {
                    best.getCandidates().add(feasibleCandidates.get(order.get(k)));
                }
                best.getCandidates().addAll(candidateLog);
                explainScheduled(best, cart, in.getAnchor());
                out.getResults().add(best);
                out.setScheduledCount(out.getScheduledCount() + 1);
                // 落子到该缸时间轴
                EVat chosen2 = vatById.get(best.getVatId());
                TimelineEntry te = TimelineEntry.placed(best, cart, chosen2);
                addEntry(timelines, chosen2.getId(), te);
            } else {
                EResult ex = new EResult();
                ex.setCartId(cart.getId());
                ex.setCartCode(cart.getCode());
                ex.setResultType("EXCLUDED");
                ex.setFamilyCode(cart.getFamilyCode());
                List<String> points = new ArrayList<>();
                if (!horizonBlockers.isEmpty()) {
                    points.add("所有适配缸在排程视窗内都排不下：" + String.join("；", horizonBlockers));
                } else {
                    points.add("没有可用缸位（机型/容量均不满足）");
                }
                ex.setReason(String.join("；", points));
                ex.getReasonPoints().addAll(points);
                ex.getCandidates().addAll(candidateLog);
                out.getResults().add(ex);
                out.setExcludedCount(out.getExcludedCount() + 1);
            }
        }

        // 硬校验排除的车最后统一输出（保证每辆布车都有交代）
        for (ECart cart : in.getCarts()) {
            List<String> reasons = exclusionReasons.get(cart.getId());
            if (reasons == null) {
                continue;
            }
            EResult ex = new EResult();
            ex.setCartId(cart.getId());
            ex.setCartCode(cart.getCode());
            ex.setResultType("EXCLUDED");
            ex.setFamilyCode(cart.getFamilyCode());
            ex.setReason(String.join("；", reasons));
            ex.getReasonPoints().addAll(reasons);
            out.getResults().add(ex);
            out.setExcludedCount(out.getExcludedCount() + 1);
        }

        // 所有动态车落子后，再按最终时间轴回填每段的真实入缸清洗。锁定段的清洗口也在此处保留，
        // 可识别维护跨段、动态插入和承诺段前空间不足等具体情况。
        postprocessWash(out, timelines, in);
        summarize(out, timelines, in);
        return out;
    }

    // ------------------------------------------------------------------
    // 落点搜索：在某缸时间轴上找第一个能容纳「清洗 + 染色」且不撞固定段的区间。
    // 维护段不改变缸内色族（清洗接续要跨过维护段找上一缸染色色族）。
    // ------------------------------------------------------------------
    private Placement earliestPlacement(List<TimelineEntry> axis, ECart cart, EVat vat,
                                        LocalDateTime anchor, LocalDateTime horizonEnd) {
        Placement p = new Placement();
        int duration = cart.getProcessMinutes();
        LocalDateTime cursor = anchor;
        List<String> blockers = new ArrayList<>();

        for (int i = 0; i < axis.size(); i++) {
            TimelineEntry e = axis.get(i);
            if (!e.end.isAfter(cursor)) {
                continue; // 已过去的段
            }
            if (e.start.isAfter(cursor)) {
                TimelineEntry colorBase = latestColoredBefore(axis, i, e.start);
                Lookup incoming = washAfter(colorBase, cart.getFamilyId());
                LocalDateTime earliestStart = cursor.plusMinutes(incoming.minutes());
                if (!earliestStart.isAfter(e.start.minusMinutes(duration))) {
                    int outgoing = outgoingWashNeeded(axis, i, e, cart.getFamilyId());
                    long gapMin = ChronoUnit.MINUTES.between(cursor, e.start);
                    if (gapMin >= incoming.minutes() + duration + outgoing) {
                        Placement ok = new Placement();
                        ok.feasible = true;
                        ok.start = earliestStart;
                        fillWash(ok, incoming, colorBase);
                        return ok;
                    }
                    blockers.add(describeGapBlock(e, incoming.minutes(), duration, outgoing));
                }
            }
            if (e.end.isAfter(cursor)) {
                cursor = e.end;
            }
        }

        // 尾部空档
        TimelineEntry colorBase = latestColoredBefore(axis, axis.size(), cursor);
        Lookup wash = washAfter(colorBase, cart.getFamilyId());
        LocalDateTime start = cursor.plusMinutes(wash.minutes());
        if (!start.plusMinutes(duration).isAfter(horizonEnd)) {
            Placement ok = new Placement();
            ok.feasible = true;
            ok.start = start;
            fillWash(ok, wash, colorBase);
            return ok;
        }
        p.feasible = false;
        p.rejectCode = "HORIZON";
        p.rejectText = "最早可用 " + fmt(cursor) + "，加清洗 " + wash.minutes()
                + " 分 + 染色 " + duration + " 分后超出视窗 " + fmt(horizonEnd)
                + (blockers.isEmpty() ? "" : "；前方受限：" + String.join("；", blockers));
        return p;
    }

    /**
     * 动态段落在维护后又紧跟固定染色/锁定段时，必须在维护前为“维护后接续染色”预留出口清洗。
     * 动态段之间不预留：后续车会作为新车重找入口清洗，不会沿用本车结束后的时间点。
     */
    private int outgoingWashNeeded(List<TimelineEntry> axis, int blockIndex, TimelineEntry block, Long cartFamilyId) {
        if ("MAINTENANCE".equals(block.kind)) {
            TimelineEntry after = nextColoredAtOrAfter(axis, blockIndex, block.end);
            // 维护后若紧跟另一辆已自动排入车，后续车自己会重算入口清洗；只对锁定/在制固定段提前预留。
            if (after == null || after.familyId == null || "PLACED".equals(after.kind)) {
                return 0;
            }
            return matrix.lookup(cartFamilyId, after.familyId).minutes();
        }
        if (block.familyId != null) {
            if ("LOCK".equals(block.kind) || "RUNNING".equals(block.kind)) {
                return matrix.lookup(cartFamilyId, block.familyId).minutes();
            }
        }
        return 0;
    }

    private TimelineEntry nextColoredAtOrAfter(List<TimelineEntry> axis, int startIndex, LocalDateTime time) {
        for (int i = startIndex; i < axis.size(); i++) {
            TimelineEntry e = axis.get(i);
            if (!e.start.isBefore(time) && e.familyId != null) {
                return e;
            }
        }
        return null;
    }

    private String describeGapBlock(TimelineEntry block, int incomingWash, int duration, int outgoingWash) {
        String name = switch (block.kind) {
            case "MAINTENANCE" -> "维护窗口";
            case "LOCK" -> block.familyId == null ? "人工封缸段" : "人工锁定染色段";
            case "RUNNING" -> "在制固定段";
            default -> "已排入段";
        };
        int required = incomingWash + duration + outgoingWash;
        return name + " " + fmt(block.start) + " ~ " + fmt(block.end)
                + " 前空档不足（需入口清洗 " + incomingWash + " + 染色 " + duration
                + (outgoingWash > 0 ? " + 接续清洗 " + outgoingWash : "")
                + " = " + required + " 分钟）";
    }

    /** 截止 beforeExclusive 索引之前、end ≤ beforeTime 的最近一个带色族段（维护段无家族，被跨过）。 */
    private TimelineEntry latestColoredBefore(List<TimelineEntry> axis, int beforeExclusive, LocalDateTime beforeTime) {
        TimelineEntry found = null;
        for (int i = 0; i < beforeExclusive && i < axis.size(); i++) {
            TimelineEntry e = axis.get(i);
            if (!e.end.isAfter(beforeTime) && e.familyId != null) {
                found = e;
            }
        }
        return found;
    }

    private Lookup washAfter(TimelineEntry prev, Long toFamilyId) {
        if (prev == null || prev.familyId == null) {
            return matrix.neutralTo(toFamilyId);
        }
        return matrix.lookup(prev.familyId, toFamilyId);
    }

    private void fillWash(Placement p, Lookup wash, TimelineEntry prev) {
        p.washMin = wash.minutes();
        p.washCost = wash.cost();
        p.switchType = wash.switchType();
        p.washFromCartId = prev == null ? null : prev.cartId;
        p.washNote = wash.note();
        p.washFallback = wash.fallback();
    }

    private void addEntry(Map<Long, List<TimelineEntry>> timelines, Long vatId, TimelineEntry e) {
        List<TimelineEntry> axis = timelines.get(vatId);
        if (axis == null) {
            return; // 已删除/不存在的缸，忽略
        }
        axis.add(e);
        axis.sort(Comparator.comparing(x -> x.start));
    }

    // ------------------------------------------------------------------
    // 结果解释
    // ------------------------------------------------------------------
    private EResult toResult(ECart cart, EVat vat, Placement p) {
        EResult r = new EResult();
        r.setCartId(cart.getId());
        r.setCartCode(cart.getCode());
        r.setResultType("SCHEDULED");
        r.setVatId(vat.getId());
        r.setVatCode(vat.getCode());
        r.setStart(p.start);
        r.setEnd(p.start.plusMinutes(cart.getProcessMinutes()));
        r.setWashStartTime(p.start.minusMinutes(p.washMin));
        r.setWashEndTime(p.start);
        r.setWashBeforeMin(p.washMin);
        r.setWashBeforeCost(p.washCost);
        r.setWashFromCartId(p.washFromCartId);
        r.setSwitchType(p.switchType);
        r.setFamilyCode(cart.getFamilyCode());
        r.setLateMinutes(p.lateMinutes);
        r.setLocked(false);
        r.getReasonPoints().add(p.washNote + "（" + p.washMin + " 分钟 / "
                + (p.washCost == null ? "0" : p.washCost.stripTrailingZeros().toPlainString()) + " 元）");
        return r;
    }

    private void explainScheduled(EResult r, ECart cart, LocalDateTime anchor) {
        List<String> pts = r.getReasonPoints();
        if (r.getLateMinutes() > 0) {
            pts.add((cart.isRush() ? "急单仍逾期 " : "预计逾期 ") + (r.getLateMinutes() / 60.0) + " 小时：该缸是兼顾清洗与交期后的最优落点");
        } else if (cart.getDueTime() != null) {
            long ahead = ChronoUnit.MINUTES.between(r.getEnd(), cart.getDueTime());
            pts.add("计划完成 " + fmt(r.getEnd()) + "，早于交期 " + fmt(cart.getDueTime())
                    + "（提前 " + (ahead / 60.0) + " 小时）");
        } else {
            pts.add("无交期约束，按整体空档与清洗成本插入最早可行缸位");
        }
        if (r.getWashBeforeMin() != null && r.getWashBeforeMin() == 0) {
            pts.add("空缸中性起步，不产生清洗占时");
        }
        if (cart.isRush()) {
            pts.add("急单优先级最高，允许以更高清洗成本换取最早完成时间，不会被省清洗规则压后");
        }
        ECandidate chosen = r.getCandidates().get(0);
        List<String> better = r.getCandidates().stream()
                .filter(c -> c.isFeasible() && !c.getVatId().equals(chosen.getVatId()))
                .map(c -> c.getVatCode() + "（最早 " + fmt(c.getEarliestStart())
                        + "，清洗 " + c.getWashMin() + " 分/"
                        + (c.getWashCost() == null ? "0" : c.getWashCost().stripTrailingZeros().toPlainString()) + " 元）")
                .toList();
        if (!better.isEmpty()) {
            pts.add("其他适配缸对比：" + String.join("、", better));
        }
        r.setReason(String.join("；", pts));
    }

    private EResult fixedResult(EBlock b, ScheduleInput in) {
        EResult r = new EResult();
        r.setResultType("FIXED");
        r.setCartId(b.getCartId());
        r.setCartCode(b.getCartCode());
        r.setVatId(b.getVatId());
        r.setVatCode(b.getVatCode());
        r.setStart(b.getStart());
        r.setEnd(b.getEnd());
        r.setLocked(false);
        r.setFamilyCode(familyCode(in, b.getFamilyId()));
        r.setReason("在制缸段（" + fmt(b.getStart()) + " ~ " + fmt(b.getEnd()) + "），排程锚点前已开缸，重算不移动");
        r.getReasonPoints().add(r.getReason());
        return r;
    }

    private EResult lockedResult(EBlock b, ScheduleInput in) {
        EResult r = new EResult();
        r.setResultType("FIXED");
        r.setCartId(b.getCartId());
        r.setCartCode(b.getCartCode());
        r.setVatId(b.getVatId());
        r.setVatCode(b.getVatCode());
        r.setStart(b.getStart());
        r.setEnd(b.getEnd());
        r.setLocked(true);
        r.setFamilyCode(familyCode(in, b.getFamilyId()));
        r.setReason("人工锁定的承诺缸位：" + b.getReason() + "；重算只调整其后的未锁定区段");
        r.getReasonPoints().add(r.getReason());
        return r;
    }

    private String familyCode(ScheduleInput in, Long familyId) {
        return in.getFamilies().stream().filter(f -> f.getId().equals(familyId)).findFirst()
                .map(EFamily::getCode).orElse(null);
    }

    /**
     * 动态排程全部结束后，按最终时间轴重新计算每个染色段的入缸前清洗。
     * 对人工锁定段也只回填清洗信息，不移动锁定染色时段；空间不足时输出资料/固定段冲突警告。
     */
    private void postprocessWash(ScheduleOutput out, Map<Long, List<TimelineEntry>> timelines, ScheduleInput in) {
        Map<String, EResult> resultByCartAndStart = new HashMap<>();
        for (EResult r : out.getResults()) {
            if (r.getCartId() != null && r.getStart() != null) {
                resultByCartAndStart.put(r.getCartId() + "@" + r.getStart(), r);
            }
        }
        Map<Long, ECart> cartById = in.getCarts().stream().collect(java.util.stream.Collectors.toMap(
                ECart::getId, c -> c, (a, b) -> a));

        for (List<TimelineEntry> axis : timelines.values()) {
            axis.sort(Comparator.comparing((TimelineEntry e) -> e.start).thenComparing(e -> e.end));
            for (int i = 0; i < axis.size(); i++) {
                TimelineEntry target = axis.get(i);
                if (target.familyId == null || target.cartId == null
                        || (!"PLACED".equals(target.kind) && !"LOCK".equals(target.kind))) {
                    continue;
                }
                EResult r = resultByCartAndStart.get(target.cartId + "@" + target.start);
                if (r == null) {
                    continue;
                }
                TimelineEntry base = latestColoredBefore(axis, i, target.start);
                LocalDateTime baseEnd = base == null || base.end.isBefore(in.getAnchor())
                        ? in.getAnchor() : base.end;
                Lookup wash = washAfter(base, target.familyId);
                Interval interval = findWashInterval(axis, i, target.start, in.getAnchor(),
                        baseEnd, wash.minutes());
                r.setWashBeforeMin(wash.minutes());
                r.setWashBeforeCost(wash.cost());
                r.setWashFromCartId(base == null ? null : base.cartId);
                r.setSwitchType(wash.switchType());
                r.setLateMinutes((int) lateMinutes(r.getEnd(), cartById.get(target.cartId) == null
                        ? null : cartById.get(target.cartId).getDueTime()));
                if (interval != null) {
                    r.setWashStartTime(interval.start());
                    r.setWashEndTime(interval.end());
                    if (r.isLocked()) {
                        String washText = "锁定前清洗已保留：" + wash.note() + "（" + fmt(interval.start())
                                + " ~ " + fmt(interval.end()) + "，" + wash.minutes() + " 分钟 / "
                                + wash.cost().stripTrailingZeros().toPlainString() + " 元）";
                        replaceWashReason(r, washText);
                    }
                } else {
                    r.setWashStartTime(null);
                    r.setWashEndTime(null);
                    String conflict = "人工锁定段 " + r.getCartCode() + "（" + r.getVatCode() + " "
                            + fmt(r.getStart()) + "）前没有连续 " + wash.minutes()
                            + " 分钟可用于「" + wash.note() + "」，锁定段与维护/占用资料冲突；请调整锁定或维护时段";
                    if (r.isLocked()) {
                        replaceWashReason(r, conflict + "；本次未移动锁定段");
                        out.getWarnings().add(conflict);
                    }
                }
            }
        }
    }

    private void replaceWashReason(EResult r, String washText) {
        java.util.List<String> pts = new ArrayList<>();
        pts.add(washText);
        pts.addAll(r.getReasonPoints().stream()
                .filter(t -> t == null || (!t.contains("清洗") && !t.contains("空缸中性起步")))
                .toList());
        r.getReasonPoints().clear();
        r.getReasonPoints().addAll(pts);
        r.setReason(String.join("；", pts));
    }

    /**
     * 在 targetStart 之前、notBefore 之后寻找一个不占用染色/维护段的清洗口。
     * 维护段被跳过；倒序找最近一个可用口，保证锁定前即使隔着维护也能预留清洗。
     */
    private Interval findWashInterval(List<TimelineEntry> axis, int targetIndex,
                                      LocalDateTime targetStart, LocalDateTime anchor,
                                      LocalDateTime notBefore, int minutes) {
        LocalDateTime candidateEnd = targetStart;
        LocalDateTime lower = notBefore.isBefore(anchor) ? anchor : notBefore;
        for (int i = targetIndex - 1; i >= 0; i--) {
            TimelineEntry e = axis.get(i);
            if (e.kind == null) {
                continue;
            }
            if (!e.end.isAfter(lower) || !e.start.isBefore(candidateEnd)) {
                continue;
            }
            if (minutesBetweenSafe(e.end, candidateEnd) >= minutes) {
                LocalDateTime start = candidateEnd.minusMinutes(minutes);
                if (!start.isBefore(e.end) && !start.isBefore(lower)) {
                    return new Interval(start, candidateEnd);
                }
            }
            candidateEnd = e.start;
        }
        if (minutesBetweenSafe(lower, candidateEnd) >= minutes) {
            return new Interval(candidateEnd.minusMinutes(minutes), candidateEnd);
        }
        return null;
    }

    private long minutesBetweenSafe(LocalDateTime start, LocalDateTime end) {
        return start.isAfter(end) ? 0 : ChronoUnit.MINUTES.between(start, end);
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------
    private void summarize(ScheduleOutput out, Map<Long, List<TimelineEntry>> timelines, ScheduleInput in) {
        int washMin = 0;
        BigDecimal washCost = BigDecimal.ZERO;
        int late = 0;
        int lateMinSum = 0;
        int idleSum = 0;
        for (EResult r : out.getResults()) {
            boolean effectiveWash = "SCHEDULED".equals(r.getResultType()) || r.getWashStartTime() != null;
            if (effectiveWash && r.getWashBeforeMin() != null) {
                washMin += r.getWashBeforeMin();
            }
            if (effectiveWash && r.getWashBeforeCost() != null) {
                washCost = washCost.add(r.getWashBeforeCost());
            }
            if (r.getLateMinutes() > 0) {
                late++;
                lateMinSum += r.getLateMinutes();
            }
            if ("SCHEDULED".equals(r.getResultType()) || "DELAYED".equals(r.getResultType())) {
                idleSum += scheduledIdle(timelines.get(r.getVatId()), r, in.getAnchor());
            }
        }
        out.setTotalWashMinutes(washMin);
        out.setTotalWashCost(washCost);
        out.setLateCount(late);
        out.setTotalLateMinutes(lateMinSum);
        out.setTotalIdleMinutes(idleSum);
    }

    /** 该车染色开始前、同缸上一占用段（或锚点）之后的空档分钟数（已扣清洗）。 */
    private int scheduledIdle(List<TimelineEntry> axis, EResult r, LocalDateTime anchor) {
        LocalDateTime prevEnd = anchor;
        LocalDateTime washStart = r.getWashStartTime() == null
                ? r.getStart().minusMinutes(r.getWashBeforeMin() == null ? 0 : r.getWashBeforeMin())
                : r.getWashStartTime();
        for (TimelineEntry e : axis) {
            if (!e.end.isAfter(washStart) && e.end.isAfter(prevEnd)) {
                prevEnd = e.end;
            }
        }
        long gap = ChronoUnit.MINUTES.between(prevEnd, r.getStart());
        long idle = gap - (r.getWashBeforeMin() == null ? 0 : r.getWashBeforeMin());
        return (int) Math.max(0, idle);
    }

    private long idleBefore(LocalDateTime anchor, List<TimelineEntry> axis, Placement p) {
        LocalDateTime prevEnd = anchor;
        for (TimelineEntry e : axis) {
            if (!e.end.isAfter(p.start) && e.end.isAfter(prevEnd)) {
                prevEnd = e.end;
            }
        }
        long gap = ChronoUnit.MINUTES.between(prevEnd, p.start);
        return Math.max(0, gap - p.washMin);
    }

    private long lateMinutes(LocalDateTime finish, LocalDateTime due) {
        if (due == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.MINUTES.between(due, finish));
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "--" : t.toString().replace('T', ' ').substring(5, 16);
    }

    /** 缸时间轴上的一个占用段（在制/锁定/维护/已排入）。 */
    private static class TimelineEntry {
        LocalDateTime start;
        LocalDateTime end;
        Long familyId;
        Long cartId;
        String kind; // RUNNING/LOCK/MAINT/PLACED

        static TimelineEntry block(EBlock b) {
            TimelineEntry e = new TimelineEntry();
            e.start = b.getStart();
            e.end = b.getEnd();
            e.familyId = b.getFamilyId();
            e.cartId = b.getCartId();
            e.kind = b.getKind();
            return e;
        }

        static TimelineEntry placed(EResult r, ECart cart, EVat vat) {
            TimelineEntry e = new TimelineEntry();
            e.start = r.getStart();
            e.end = r.getEnd();
            e.familyId = cart.getFamilyId();
            e.cartId = cart.getId();
            e.kind = "PLACED";
            return e;
        }
    }

    /** 一次落点尝试的内部结构。 */
    private static class Placement {
        boolean feasible;
        LocalDateTime start;
        int washMin;
        BigDecimal washCost;
        String switchType;
        Long washFromCartId;
        String washNote;
        boolean washFallback;
        String rejectCode;
        String rejectText;
        int lateMinutes;
        int idleMinutes;
    }
}
