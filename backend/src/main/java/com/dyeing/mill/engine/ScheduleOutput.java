package com.dyeing.mill.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 排程引擎输出：每辆布车一条结果 + 汇总指标。结果与输入快照一起可被持久化/复算。
 */
@Data
public class ScheduleOutput {

    private final List<EResult> results = new ArrayList<>();
    private int scheduledCount;
    private int excludedCount;
    private int fixedCount;
    private int lateCount;
    private int totalWashMinutes;
    private BigDecimal totalWashCost = BigDecimal.ZERO;
    private int totalIdleMinutes;
    private int totalLateMinutes;
    private final List<String> warnings = new ArrayList<>();

    /** 固定锁定段自身资料矛盾等不适合作为布车排除项的硬约束提示。 */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * SCHEDULED 自动排入（lateMinutes>0 表示虽排入但延后逾期）/ EXCLUDED 排除 / FIXED 在制或锁定的固定段。
     */
    @Data
    public static class EResult {
        private Long cartId;
        private String cartCode;
        private String resultType;
        private Long vatId;
        private String vatCode;
        private LocalDateTime start;
        private LocalDateTime end;
        private LocalDateTime washStartTime;
        private LocalDateTime washEndTime;
        private Integer washBeforeMin;
        private BigDecimal washBeforeCost;
        private Long washFromCartId;
        private String switchType;
        private String familyCode;
        private int lateMinutes;
        private boolean locked;
        private String reason;
        /** 排入理由的结构化要点（前端时间轴 tooltip / 解释面板用） */
        private List<String> reasonPoints = new ArrayList<>();
        /** 候选缸位评估记录（为何选这缸、为何放弃其他缸） */
        private List<ECandidate> candidates = new ArrayList<>();
    }

    /** 某辆布车在某个缸的候选落点评估 */
    @Data
    public static class ECandidate {
        private Long vatId;
        private String vatCode;
        private LocalDateTime earliestStart;
        private Integer washMin;
        private BigDecimal washCost;
        private String switchType;
        private boolean feasible;
        /** 不可行或未选原因：CAPACITY/MACHINE/MAINTENANCE/HORIZON */
        private String rejectCode;
        private String rejectText;
        /** 综合评分（越小越好） */
        private long score;
    }
}
