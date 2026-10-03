package com.dyeing.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.ClothCart;
import com.dyeing.mill.domain.DyeVat;
import com.dyeing.mill.domain.MaintenanceWindow;
import com.dyeing.mill.domain.Recipe;
import com.dyeing.mill.domain.ScheduleLock;
import com.dyeing.mill.dto.CartSchedulePatchRequest;
import com.dyeing.mill.dto.MaintenanceWindowRequest;
import com.dyeing.mill.dto.ScheduleLockRequest;
import com.dyeing.mill.repository.ClothCartRepository;
import com.dyeing.mill.repository.ColorFamilyMapRepository;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.FabricMachineRepository;
import com.dyeing.mill.repository.MaintenanceWindowRepository;
import com.dyeing.mill.repository.RecipeRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;

/**
 * 排程台基础数据维护：
 *  - 布车交期/急单：只改排程字段，严禁改布车状态、排缸结果（不触碰开缸执行单）；
 *  - 维护时段、人工锁定段的增删查；锁定段互斥校验（同一缸时段不能重叠锁定）。
 */
@Service
public class ScheduleMasterDataService {

    private final ClothCartRepository cartRepository;
    private final DyeVatRepository vatRepository;
    private final MaintenanceWindowRepository maintenanceRepository;
    private final ScheduleLockRepository lockRepository;
    private final ColorFamilyMapRepository familyMapRepository;
    private final FabricMachineRepository fabricMachineRepository;
    private final RecipeRepository recipeRepository;

    public ScheduleMasterDataService(ClothCartRepository cartRepository,
                                     DyeVatRepository vatRepository,
                                     MaintenanceWindowRepository maintenanceRepository,
                                     ScheduleLockRepository lockRepository,
                                     ColorFamilyMapRepository familyMapRepository,
                                     FabricMachineRepository fabricMachineRepository,
                                     RecipeRepository recipeRepository) {
        this.cartRepository = cartRepository;
        this.vatRepository = vatRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.lockRepository = lockRepository;
        this.familyMapRepository = familyMapRepository;
        this.fabricMachineRepository = fabricMachineRepository;
        this.recipeRepository = recipeRepository;
    }

    // ---------------- 布车交期 / 急单（仅排程字段） ----------------

    @Transactional
    public ClothCart patchCartSchedule(Long cartId, CartSchedulePatchRequest request) {
        ClothCart cart = cartRepository.findByIdForUpdate(cartId)
                .orElseThrow(() -> new BizException("布车不存在或已报废：id=" + cartId));
        if (request.getDueTime() != null) {
            if (request.getDueTime().isBlank()) {
                cart.setDueTime(null);
            } else {
                cart.setDueTime(parseTime(request.getDueTime(), "交期"));
            }
        }
        if (request.getRushFlag() != null) {
            int flag = request.getRushFlag();
            if (flag != 0 && flag != 1) {
                throw new BizException("急单标记只能是 0 或 1");
            }
            cart.setRushFlag(flag);
        }
        return cartRepository.save(cart);
    }

    // ---------------- 维护时段 ----------------

    @Transactional(readOnly = true)
    public List<MaintenanceWindow> listMaintenance() {
        return maintenanceRepository.findAllOrdered();
    }

    @Transactional
    public MaintenanceWindow createMaintenance(MaintenanceWindowRequest request) {
        DyeVat vat = requireVat(request.getVatId());
        LocalDateTime start = parseTime(request.getStartTime(), "维护开始时间");
        LocalDateTime end = parseTime(request.getEndTime(), "维护结束时间");
        if (!end.isAfter(start)) {
            throw new BizException("维护结束时间必须晚于开始时间");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BizException("维护原因不能为空");
        }
        assertNoLockOverlap(vat.getId(), vat.getVatCode(), start, end);
        MaintenanceWindow w = new MaintenanceWindow();
        w.setVatId(vat.getId());
        w.setVatCode(vat.getVatCode());
        w.setStartTime(start);
        w.setEndTime(end);
        w.setReason(request.getReason().trim());
        w.setCreatedBy(normalize(request.getCreatedBy(), "设备科"));
        w.setCreateTime(LocalDateTime.now());
        return maintenanceRepository.save(w);
    }

    @Transactional
    public void deleteMaintenance(Long id) {
        MaintenanceWindow w = maintenanceRepository.findById(id)
                .orElseThrow(() -> new BizException("维护时段不存在：id=" + id));
        w.setDeleted(1);
        maintenanceRepository.save(w);
    }

    // ---------------- 人工锁定段 ----------------

    @Transactional(readOnly = true)
    public List<ScheduleLock> listLocks() {
        return lockRepository.findAllOrdered();
    }

    @Transactional
    public ScheduleLock createLock(ScheduleLockRequest request) {
        DyeVat vat = requireVat(request.getVatId());
        LocalDateTime start = parseTime(request.getStartTime(), "锁定开始时间");
        LocalDateTime end = parseTime(request.getEndTime(), "锁定结束时间");
        if (!end.isAfter(start)) {
            throw new BizException("锁定结束时间必须晚于开始时间");
        }
        if (request.getLockReason() == null || request.getLockReason().isBlank()) {
            throw new BizException("锁定原因（承诺依据）不能为空");
        }
        if (end.isBefore(java.time.LocalDateTime.now())) {
            throw new BizException("不能锁定已经结束的历史时段");
        }
        // 同缸与其他锁定段互斥
        List<ScheduleLock> overlap = lockRepository.findOverlap(vat.getId(), start, end);
        if (!overlap.isEmpty()) {
            ScheduleLock c = overlap.get(0);
            throw new BizException("染缸 " + vat.getVatCode() + " 在该时段已存在锁定段 "
                    + c.getStartTime() + " ~ " + c.getEndTime() + "（" + c.getLockReason() + "）");
        }
        if (!maintenanceRepository.findOverlap(vat.getId(), start, end).isEmpty()) {
            throw new BizException("染缸 " + vat.getVatCode() + " 在该时段已有维护安排，不能再承诺锁定缸位");
        }
        assertNoRunningOverlap(vat.getId(), vat.getVatCode(), start, end);
        String cartCode = null;
        if (request.getCartId() != null) {
            ClothCart cart = cartRepository.findById(request.getCartId())
                    .orElseThrow(() -> new BizException("锁定的布车不存在或已报废：id=" + request.getCartId()));
            if (!"PENDING".equals(cart.getStatus()) && !"REWORK".equals(cart.getStatus())) {
                throw new BizException("只能锁定待排产或返修布车；" + cart.getCartCode() + " 当前为 " + cart.getStatus());
            }
            if (familyMapRepository.findByColorCode(cart.getColorCode()).isEmpty()) {
                throw new BizException("布车 " + cart.getCartCode() + " 的色号未配置颜色族，无法预留锁定前清洗");
            }
            assertLockCartCompatible(cart, vat);
            cartCode = cart.getCartCode();
        }
        ScheduleLock lock = new ScheduleLock();
        lock.setVatId(vat.getId());
        lock.setVatCode(vat.getVatCode());
        lock.setCartId(request.getCartId());
        lock.setCartCode(cartCode);
        lock.setStartTime(start);
        lock.setEndTime(end);
        lock.setLockReason(request.getLockReason().trim());
        lock.setCreatedBy(normalize(request.getCreatedBy(), "调度员"));
        lock.setCreateTime(LocalDateTime.now());
        return lockRepository.save(lock);
    }

    @Transactional
    public void deleteLock(Long id) {
        ScheduleLock lock = lockRepository.findById(id)
                .orElseThrow(() -> new BizException("锁定段不存在：id=" + id));
        lock.setDeleted(1);
        lockRepository.save(lock);
    }

    private void assertNoLockOverlap(Long vatId, String vatCode, LocalDateTime start, LocalDateTime end) {
        List<ScheduleLock> overlap = lockRepository.findOverlap(vatId, start, end);
        if (!overlap.isEmpty()) {
            ScheduleLock l = overlap.get(0);
            throw new BizException("染缸 " + vatCode + " 在该维护时段已有承诺锁定段 "
                    + l.getStartTime() + " ~ " + l.getEndTime() + "（" + l.getLockReason() + "），请先解除或改期");
        }
    }

    private void assertNoRunningOverlap(Long vatId, String vatCode, LocalDateTime start, LocalDateTime end) {
        List<ClothCart> running = cartRepository.findOverlap(vatId, -1L, start, end);
        if (!running.isEmpty()) {
            ClothCart c = running.get(0);
            throw new BizException("染缸 " + vatCode + " 在该时段已有在制布车 " + c.getCartCode()
                    + "（" + c.getDyeStartTime() + " ~ " + c.getDyeEndTime() + "）");
        }
    }

    private void assertLockCartCompatible(ClothCart cart, DyeVat vat) {
        boolean machineFit = fabricMachineRepository
                .existsByFabricTypeAndMachineType(cart.getFabricType(), vat.getMachineType());
        if (!machineFit) {
            throw new BizException("布车 " + cart.getCartCode() + " 的布种 " + cart.getFabricType()
                    + " 不能使用 " + vat.getVatCode() + " 的机型 " + vat.getMachineType());
        }
        boolean recipeReady = recipeRepository.findAll().stream()
                .anyMatch(r -> "RELEASED".equals(r.getStatus())
                        && r.getFabricType().equals(cart.getFabricType())
                        && r.getColorCode().equals(cart.getColorCode())
                        && r.getTotalOwf() != null
                        && r.getTotalOwf().compareTo(new BigDecimal("100.000")) == 0);
        if (!recipeReady) {
            throw new BizException("布车 " + cart.getCartCode() + " 没有 owf=100% 的已发布配方，不能承诺锁定");
        }
        BigDecimal weight = cart.getMeters() == null ? BigDecimal.ZERO
                : cart.getMeters().multiply(ScheduleInputAssembler.METERS_TO_KG);
        if (vat.getCapacityKg() == null || vat.getCapacityKg().compareTo(weight) < 0) {
            throw new BizException("布车 " + cart.getCartCode() + " 折算 " + weight.stripTrailingZeros().toPlainString()
                    + "kg，超过 " + vat.getVatCode() + " 容量 " + vat.getCapacityKg() + "kg，不能承诺锁定");
        }
    }

    private DyeVat requireVat(Long vatId) {
        if (vatId == null) {
            throw new BizException("请选择染缸");
        }
        return vatRepository.findById(vatId)
                .orElseThrow(() -> new BizException("染缸不存在：id=" + vatId));
    }

    private LocalDateTime parseTime(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(label + "不能为空");
        }
        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (DateTimeParseException e) {
            throw new BizException(label + "格式有误，应为 yyyy-MM-dd HH:mm");
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String t = value.trim();
        return t.length() > 32 ? t.substring(0, 32) : t;
    }
}
