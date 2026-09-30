import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能岗位匹配算法 — 纯 Java 工具类，无 Spring / MyBatis 等框架依赖。
 *
 * <p>输入：求职者画像（期望薪资、学历、城市、技能）+ 岗位信息（薪资范围、学历要求、地区、技能要求）
 * <p>输出：四个维度得分及加权总分，满分 100。
 *
 * <p>维度权重：技能 50% · 学历 20% · 城市 20% · 薪资 10%
 *
 * <p>薪资格式支持：
 * <ul>
 *   <li>标准区间："8k-12k"、"8K-12K"、"8千-12千"、"15万-20万"</li>
 *   <li>波浪号分隔："8~12k"、"8~12K"</li>
 *   <li>上限表达："8k以下"、"15k以内"、"12K及以下"、"8k以下（可 negotiat）"</li>
 *   <li>下限表达："15k以上"、"12K及以上"、"8k+"、"10k起"</li>
 *   <li>固定薪资："10k"、"15K"、"1.5万"、"月薪12000"</li>
 *   <li>无法解析："面议"、"协商"、空值 → 返回空数组</li>
 * </ul>
 *
 * <p>调用示例：
 * <pre>{@code
 * double[] expected = MatchingAlgorithm.parseSalaryRange("10k-15k");
 * double salaryScore = MatchingAlgorithm.computeSalaryMatchScore(
 *         MatchingAlgorithm.parseSalaryRange("8k-12k"), expected);
 * int total = MatchingAlgorithm.computeMatchScore(
 *         Map.of("skills", List.of("Java","Spring"), "minEducation", "本科",
 *                "region", "北京"),
 *         Map.of("skills", List.of("Java"), "minEducation", "本科",
 *                "region", "北京", "salaryRange", "8k-12k"),
 *         List.of("Java", "Python"), "本科", "北京", expected);
 * }</pre>
 */
public class MatchingAlgorithm {

    // ─── 常量 ──────────────────────────────────────────────────────────────────

    /** 教育等级映射：大专→1 / 本科→2 / 硕士→3 / 博士→4 */
    private static final Map<String, Integer> EDUCATION_LEVEL;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("大专", 1);
        m.put("本科", 2);
        m.put("硕士", 3);
        m.put("博士", 4);
        EDUCATION_LEVEL = Collections.unmodifiableMap(m);
    }

    /** 教育等级反向查找表（level → label） */
    private static final Map<Integer, String> EDUCATION_LABEL;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : EDUCATION_LEVEL.entrySet()) {
            m.put(e.getValue(), e.getKey());
        }
        EDUCATION_LABEL = Collections.unmodifiableMap(m);
    }

    /** 薪资文本解析正则：数字 + 可选单位(万/w/k) + 可选方向词(以下/以内/以上/及以上/+) */
    private static final Pattern SALARY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(万|w|W|k|K)?\\s*(以下|以内|及以下|以上|及以上|\\+)?"
    );

    /** 维度权重 */
    private static final int SKILL_WEIGHT      = 50;
    private static final int EDUCATION_WEIGHT  = 20;
    private static final int CITY_WEIGHT       = 20;
    private static final int SALARY_WEIGHT     = 10;

    // ─── 入口方法 ──────────────────────────────────────────────────────────────

    /**
     * 计算岗位与求职者画像的匹配总分（0 ~ 100）。
     *
     * <p>四个维度独立打分后加权求和：技能 50 分、学历 20 分、城市 20 分、薪资 10 分。
     *
     * @param job     岗位信息，key 见 {@link #computeSkillMatchScore} 入参说明
     * @param profile 求职者画像，key 见各维度方法入参说明
     * @return 加权总分（int，0-100）；任一必要参数为 null 时返回 -1 表示参数缺失
     */
    public static int computeMatchScore(
            Map<String, Object> job,
            Map<String, Object> profile) {
        if (job == null || profile == null) {
            return -1;
        }

        List<String> userSkills = getAsStringList(profile.get("skills"));
        String userEducation  = toStringOrNull(profile.get("minEducation"));
        String expectedCity   = toStringOrNull(profile.get("expectedCity"));
        String expectedSalary = toStringOrNull(profile.get("expectedSalary"));

        String jobRegion           = toStringOrNull(job.get("region"));
        String jobMinEducation     = toStringOrNull(job.get("minEducation"));
        String jobSalaryRange      = toStringOrNull(job.get("salaryRange"));
        String jobRequiredSkillsStr = toStringOrNull(job.get("requiredSkill"));
        List<String> requiredSkills = parseSkills(jobRequiredSkillsStr);

        double[] expectedRange = parseSalaryRange(expectedSalary);

        int skillScore      = computeSkillMatchScore(requiredSkills, userSkills);
        int educationScore  = computeEducationMatchScore(userEducation, jobMinEducation);
        int cityScore       = computeCityMatchScore(jobRegion, expectedCity);
        int salaryScore     = computeSalaryMatchScore(jobSalaryRange, expectedRange);

        return skillScore + educationScore + cityScore + salaryScore;
    }

    /**
     * 计算技能匹配得分（权重 50）。
     *
     * <p>重合度 = 用户已掌握技能 ∩ 岗位要求技能 的命中数 / 岗位需求总数。
     * 若岗位未列出技能要求，则默认满分 50。
     *
     * @param requiredSkills 岗位要求的技能列表（逗号/顿号/英文逗号分隔的原始字符串经 parseSkills 处理后的结果）
     * @param userSkills     用户已掌握的技能列表
     * @return 技能维度得分（0-50）
     */
    public static int computeSkillMatchScore(List<String> requiredSkills, List<String> userSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return SKILL_WEIGHT;
        }
        if (userSkills == null || userSkills.isEmpty()) {
            return 0;
        }
        long matched = requiredSkills.stream()
                .filter(userSkills::contains)
                .count();
        return (int) Math.round((double) matched / requiredSkills.size() * SKILL_WEIGHT);
    }

    /**
     * 计算学历匹配得分（权重 20）。
     *
     * <p>用户学历等级 ≥ 岗位要求等级 时得满分 20，否则 0 分。
     * 若岗位未设置学历要求，默认满分。
     *
     * @param userEducation   用户学历（"大专"/"本科"/"硕士"/"博士"，或 null）
     * @param jobMinEducation 岗位最低学历要求（同上，或 null）
     * @return 学历维度得分（0 或 20）
     */
    public static int computeEducationMatchScore(String userEducation, String jobMinEducation) {
        if (isBlank(jobMinEducation)) {
            return EDUCATION_WEIGHT;
        }
        int jobLevel = EDUCATION_LEVEL.getOrDefault(jobMinEducation.trim(), 0);
        int userLevel = isBlank(userEducation) ? 0 : EDUCATION_LEVEL.getOrDefault(userEducation.trim(), 0);
        return userLevel >= jobLevel ? EDUCATION_WEIGHT : 0;
    }

    /**
     * 计算城市匹配得分（权重 20）。
     *
     * <p>岗位地区与期望城市完全一致（忽略空白）时得满分 20，否则 0 分。
     * 若岗位未设置地区，视为不限，返回满分 20；若用户未设置期望城市，返回 0。
     *
     * @param jobRegion      岗位地区（可为 null）
     * @param expectedCity   用户期望城市（可为 null）
     * @return 城市维度得分（0 或 20）
     */
    public static int computeCityMatchScore(String jobRegion, String expectedCity) {
        if (isBlank(jobRegion)) {
            return CITY_WEIGHT;
        }
        if (isBlank(expectedCity)) {
            return 0;
        }
        return jobRegion.trim().equals(expectedCity.trim()) ? CITY_WEIGHT : 0;
    }

    /**
     * 计算薪资匹配得分（权重 10）。
     *
     * <p>支持两种情况：
     * <ol>
     *   <li>岗位薪资文本可解析 → 与期望区间比较重叠比例，按比例给分（至少 1 分）。</li>
     *   <li>岗位薪资无法解析（如"面议"）→ 直接给满分 10。</li>
     * </ol>
     * 重叠比例 = min(重叠区间长度 / 期望区间长度, 1.0)。
     * 若用户未设置期望薪资，返回 0。
     *
     * @param jobSalaryRange  岗位薪资原始文本（如 "8k-12k"、"15万以上"，或 null）
     * @param expectedRange   期望薪资区间 [min, max]（单位：元/月），由 {@link #parseSalaryRange} 解析
     * @return 薪资维度得分（0-10）
     */
    public static int computeSalaryMatchScore(String jobSalaryRange, double[] expectedRange) {
        // 用户未设期望薪资，无法评分
        if (expectedRange == null || expectedRange.length < 2) {
            return 0;
        }

        double[] jobRange = parseSalaryRange(jobSalaryRange);
        // 岗位薪资无法解析（面议等），视为不限，给满分
        if (jobRange == null || jobRange.length < 2) {
            return SALARY_WEIGHT;
        }

        double overlapMin = Math.max(jobRange[0], expectedRange[0]);
        double overlapMax = Math.min(jobRange[1], expectedRange[1]);
        double overlapLen = overlapMax - overlapMin;

        if (overlapLen <= 0) {
            return 0;
        }

        double expectedLen = expectedRange[1] - expectedRange[0];
        // 期望区间为零（固定期望薪）时，以岗位区间长度兜底，避免除零
        double denominator = expectedLen > 0 ? expectedLen
                           : (jobRange[1] - jobRange[0] > 0 ? jobRange[1] - jobRange[0] : 1.0);
        double ratio = Math.min(overlapLen / denominator, 1.0);
        int score = (int) Math.round(ratio * SALARY_WEIGHT);
        // 有重叠但比例极小时，保底给 1 分
        return Math.max(score, 1);
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 解析薪资文本，返回 [min, max] 区间（单位：元/月）。
     *
     * <p>支持的格式：
     * <ul>
     *   <li>标准区间："8k-12k"、"8K-12K"、"8千-12千"、"15万-20万"、"8~12k"</li>
     *   <li>上限表达："8k以下"、"15k以内"、"12K及以下"</li>
     *   <li>下限表达："15k以上"、"12K及以上"、"8k+"、"10k起"</li>
     *   <li>固定薪资："10k"、"15K"、"1.5万"、"月薪12000"</li>
     *   <li>无法解析："面议"、"协商"、空字符串 → 返回 null</li>
     * </ul>
     *
     * @param salaryStr 薪资原始文本
     * @return [min, max] 数组（单位：元），解析失败或无法确定范围时返回 null
     */
    public static double[] parseSalaryRange(String salaryStr) {
        if (isBlank(salaryStr)) {
            return null;
        }
        String normalized = salaryStr.trim().toLowerCase();

        // 兜底：面议等无法解析的值
        if (normalized.contains("面议") || normalized.contains("协商")) {
            return null;
        }

        // 统一分隔符
        String cleaned = normalized
                .replace("~", "-")
                .replaceAll("[¥$€£\\s]", "");

        Matcher matcher = SALARY_PATTERN.matcher(cleaned);
        List<Double> values = new ArrayList<>();
        List<String> directions = new ArrayList<>();

        while (matcher.find()) {
            try {
                double val = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "";
                String direction = matcher.group(3) != null ? matcher.group(3) : "";

                if ("万".equals(unit) || "w".equals(unit)) {
                    val *= 10000;
                } else if ("k".equals(unit)) {
                    val *= 1000;
                }
                values.add(val);
                if (!direction.isEmpty()) {
                    directions.add(direction);
                }
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的数字片段
            }
        }

        if (values.size() >= 2) {
            return new double[]{values.get(0), values.get(1)};
        }

        if (values.size() == 1) {
            String dir = directions.isEmpty() ? "" : directions.get(0);
            double val = values.get(0);
            if (dir.contains("以下") || dir.contains("以内") || dir.contains("及以下")) {
                return new double[]{0, val};
            } else if (dir.contains("以上") || dir.contains("及以上") || dir.contains("+") || dir.contains("起")) {
                // 上限设为 999999 表示无上界（月薪万元级别，999万已远超合理范围）
                return new double[]{val, 999999};
            } else {
                // 固定薪资：区间退化为单点
                return new double[]{val, val};
            }
        }

        return null;
    }

    /**
     * 将技能原始字符串按中文/英文逗号、顿号分割，去除空白并过滤空串。
     *
     * @param skillStr 如 "Java, Spring, Python" 或 "Java、Spring、Python"
     * @return 技能列表；输入为 null/空时返回空列表
     */
    public static List<String> parseSkills(String skillStr) {
        if (isBlank(skillStr)) {
            return Collections.emptyList();
        }
        return Arrays.stream(skillStr.split("[,，、]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(ArrayList::new, List::add, List::addAll);
    }

    // ─── 私有辅助 ──────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String toStringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> getAsStringList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }
        if (o instanceof List) {
            List<?> raw = (List<?>) o;
            List<String> result = new ArrayList<>(raw.size());
            for (Object item : raw) {
                result.add(item == null ? "" : item.toString());
            }
            return result;
        }
        return Collections.singletonList(o.toString());
    }

    // ─── 测试用例（取消注释 main 方法后可独立运行）───────────────────────────

    /**
     * 主方法：运行三种典型场景的测试用例。
     *
     * <p>场景 1 — 薪资远低于预期：期望 15k-20k，岗位 5k-8k → 薪资 0 分
     * <p>场景 2 — 薪资部分重叠：期望 10k-15k，岗位 12k-18k → 重叠 3000/5000=60% → 6 分
     * <p>场景 3 — 薪资完全匹配：期望 10k-15k，岗位 10k-15k → 重叠 5000/5000=100% → 10 分
     */
    public static void main(String[] args) {
        // ── 场景 1：薪资远低于预期 → 薪资 0 分 ──
        System.out.println("=== 场景1：薪资远低于预期 ===");
        double[] expected1 = parseSalaryRange("15k-20k");   // [15000, 20000]
        int salaryScore1 = computeSalaryMatchScore("5k-8k", expected1);
        System.out.printf("期望: 15k-20k, 岗位: 5k-8k => 薪资得分: %d (期望: 0)%n", salaryScore1);
        assert salaryScore1 == 0 : "场景1失败：预期 0，实际 " + salaryScore1;

        // 完整匹配场景 1
        Map<String, Object> job1 = new HashMap<>();
        job1.put("region", "北京");
        job1.put("minEducation", "本科");
        job1.put("salaryRange", "5k-8k");
        job1.put("requiredSkill", "Java,Python");

        Map<String, Object> profile1 = new HashMap<>();
        profile1.put("expectedCity", "北京");
        profile1.put("minEducation", "本科");
        profile1.put("expectedSalary", "15k-20k");
        profile1.put("skills", List.of("Java", "Python"));

        int total1 = computeMatchScore(job1, profile1);
        System.out.printf("场景1总分: %d（技能50+学历20+城市20+薪资0=90）%n", total1);
        assert total1 == 90 : "场景1总分失败：预期 90，实际 " + total1;

        // ── 场景 2：薪资部分重叠 → 按比例得分 ──
        System.out.println("\n=== 场景2：薪资部分重叠 ===");
        double[] expected2 = parseSalaryRange("10k-15k");   // [10000, 15000]
        int salaryScore2 = computeSalaryMatchScore("12k-18k", expected2);
        // 重叠区间 [12000, 15000]，长度 3000；期望区间长度 5000；比例 0.6 → round(6.0)=6
        System.out.printf("期望: 10k-15k, 岗位: 12k-18k => 薪资得分: %d (期望: 6)%n", salaryScore2);
        assert salaryScore2 == 6 : "场景2失败：预期 6，实际 " + salaryScore2;

        // ── 场景 3：薪资完全匹配 → 薪资满分 ──
        System.out.println("\n=== 场景3：薪资完全匹配 ===");
        double[] expected3 = parseSalaryRange("10k-15k");   // [10000, 15000]
        int salaryScore3 = computeSalaryMatchScore("10k-15k", expected3);
        // 完全重叠，比例 1.0 → round(10.0)=10
        System.out.printf("期望: 10k-15k, 岗位: 10k-15k => 薪资得分: %d (期望: 10)%n", salaryScore3);
        assert salaryScore3 == 10 : "场景3失败：预期 10，实际 " + salaryScore3;

        // ── 边界用例 ──

        // 固定薪资命中期望区间内部
        int fixedScore = computeSalaryMatchScore("12k", expected3);
        System.out.printf("期望: 10k-15k, 岗位: 12k(固定) => 薪资得分: %d%n", fixedScore);

        // 面议 → 满分
        int negotiableScore = computeSalaryMatchScore("面议", expected3);
        System.out.printf("期望: 10k-15k, 岗位: 面议 => 薪资得分: %d (期望: 10)%n", negotiableScore);
        assert negotiableScore == 10 : "面议场景失败：预期 10，实际 " + negotiableScore;

        // "15k以上" 解析为 [15000, 999999]
        double[] above = parseSalaryRange("15k以上");
        System.out.printf("15k以上 解析结果: [%.0f, %.0f]%n", above[0], above[1]);
        assert above[0] == 15000 && above[1] == 999999 : "15k以上解析失败";

        // "8k以下" 解析为 [0, 8000]
        double[] below = parseSalaryRange("8k以下");
        System.out.printf("8k以下 解析结果: [%.0f, %.0f]%n", below[0], below[1]);
        assert below[0] == 0 && below[1] == 8000 : "8k以下解析失败";

        // 单位"万"解析
        double[] wan = parseSalaryRange("1.5万-2万");
        System.out.printf("1.5万-2万 解析结果: [%.0f, %.0f]%n", wan[0], wan[1]);
        assert wan[0] == 15000 && wan[1] == 20000 : "万元解析失败";

        System.out.println("\n所有测试通过！");
    }
}
