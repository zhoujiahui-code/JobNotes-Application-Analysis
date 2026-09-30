-- ============================================================================
-- JobNotes 数据分析统计 SQL 语句集
-- 数据库: MySQL 8.x (utf8mb4, 表 job / note)
-- 参数说明: #{userId} — 当前用户 ID；#{weekStart} / #{weekEnd} — 本周起止日期
-- ============================================================================

-- ============================================================================
-- 模块一：投递转化漏斗统计
-- 说明：各阶段岗位数量与转化率，从"投递总量"逐级下钻至"获得Offer"
-- ============================================================================

-- 【核心指标-总投递数】当前用户累计投递岗位总数
SELECT COUNT(*) AS totalApplied
FROM job
WHERE user_id = #{userId};

-- 【核心指标-流程中】处于进行中状态的岗位数（APPLIED 或 INTERVIEW）
SELECT COUNT(*) AS inProgress
FROM job
WHERE user_id = #{userId}
  AND status IN ('APPLIED', 'INTERVIEW');

-- 【核心指标-面试中】当前处于面试阶段的岗位数
SELECT COUNT(*) AS interviewing
FROM job
WHERE user_id = #{userId}
  AND status = 'INTERVIEW';

-- 【核心指标-已获得Offer】已成功获得录用通知的岗位数
SELECT COUNT(*) AS offered
FROM job
WHERE user_id = #{userId}
  AND status = 'OFFERED';

-- 【核心指标-本周投递数】当周（周一至周日）新投递的岗位数
SELECT COUNT(*) AS appliedThisWeek
FROM job
WHERE user_id = #{userId}
  AND status = 'APPLIED'
  AND apply_date >= #{weekStart}
  AND apply_date <= #{weekEnd};

-- 【漏斗-完整四阶段】各阶段岗位数及转化率
-- stage: 阶段名 | count: 该阶段数量 | rate: 相对上一阶段转化率(%) | totalRate: 占总投递比例(%)
SELECT
    '投递总量'   AS stage,
    COUNT(*)     AS count,
    100.0        AS rate,
    100.0        AS totalRate
FROM job
WHERE user_id = #{userId}

UNION ALL

SELECT
    '通过初筛'   AS stage,
    COUNT(*)     AS count,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job WHERE user_id = #{userId}
    ), 0), 1)    AS rate,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job WHERE user_id = #{userId}
    ), 0), 1)    AS totalRate
FROM job
WHERE user_id = #{userId}
  AND status IN ('INTERVIEW', 'OFFERED', 'REJECTED')

UNION ALL

SELECT
    '进入面试'   AS stage,
    COUNT(*)     AS count,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job
        WHERE user_id = #{userId}
          AND status IN ('INTERVIEW', 'OFFERED', 'REJECTED')
    ), 0), 1)    AS rate,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job WHERE user_id = #{userId}
    ), 0), 1)    AS totalRate
FROM job
WHERE user_id = #{userId}
  AND status IN ('INTERVIEW', 'OFFERED')

UNION ALL

SELECT
    '获得Offer'  AS stage,
    COUNT(*)     AS count,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job
        WHERE user_id = #{userId}
          AND status IN ('INTERVIEW', 'OFFERED')
    ), 0), 1)    AS rate,
    ROUND(COUNT(*) * 100.0 / NULLIF((
        SELECT COUNT(*) FROM job WHERE user_id = #{userId}
    ), 0), 1)    AS totalRate
FROM job
WHERE user_id = #{userId}
  AND status = 'OFFERED';


-- ============================================================================
-- 模块二：各阶段流失率计算
-- 说明：以"通过初筛"(岗位产生反馈:进入面试/获得Offer/被拒绝)为起点，计算面试转化率与Offer转化率
-- ============================================================================

-- 【流失率-整体汇总】各状态计数 + 面试转化率 + Offer转化率
-- 面试转化率 = 进入面试(含OFFERED) / 产生反馈(INTERVIEW+OFFERED+REJECTED)
-- Offer转化率 = 获得Offer / 进入面试数
SELECT
    SUM(CASE WHEN status = 'APPLIED'                     THEN 1 ELSE 0 END) AS appliedCount,
    SUM(CASE WHEN status IN ('INTERVIEW','OFFERED','REJECTED') THEN 1 ELSE 0 END) AS withFeedbackCount,
    SUM(CASE WHEN status IN ('INTERVIEW','OFFERED')       THEN 1 ELSE 0 END) AS interviewCount,
    SUM(CASE WHEN status = 'OFFERED'                     THEN 1 ELSE 0 END) AS offerCount,
    SUM(CASE WHEN status = 'REJECTED'                    THEN 1 ELSE 0 END) AS rejectedCount,
    ROUND(
        SUM(CASE WHEN status IN ('INTERVIEW','OFFERED') THEN 1 ELSE 0 END)
        * 100.0 / NULLIF(SUM(CASE WHEN status IN ('INTERVIEW','OFFERED','REJECTED') THEN 1 ELSE 0 END), 0),
        1
    ) AS interviewRate,
    ROUND(
        SUM(CASE WHEN status = 'OFFERED' THEN 1 ELSE 0 END)
        * 100.0 / NULLIF(SUM(CASE WHEN status IN ('INTERVIEW','OFFERED') THEN 1 ELSE 0 END), 0),
        1
    ) AS offerRate
FROM job
WHERE user_id = #{userId};

-- 【流失率-分步详情】两个流失环节的独立计数
-- step1: 初筛→面试的拒绝率 = REJECTED / (INTERVIEW+OFFERED+REJECTED)
-- step2: 面试→Offer的流失率 = INTERVIEW / (INTERVIEW+OFFERED)
SELECT
    '初筛→面试'           AS step,
    SUM(CASE WHEN status IN ('INTERVIEW','OFFERED','REJECTED') THEN 1 ELSE 0 END) AS passedScreen,
    SUM(CASE WHEN status IN ('INTERVIEW','OFFERED')            THEN 1 ELSE 0 END) AS enteredInterview,
    SUM(CASE WHEN status = 'REJECTED'                          THEN 1 ELSE 0 END) AS rejectedCount,
    ROUND(
        SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END)
        * 100.0 / NULLIF(SUM(CASE WHEN status IN ('INTERVIEW','OFFERED','REJECTED') THEN 1 ELSE 0 END), 0),
        1
    ) AS churnRate
FROM job
WHERE user_id = #{userId}
  AND status IN ('INTERVIEW', 'OFFERED', 'REJECTED')

UNION ALL

SELECT
    '面试→Offer'          AS step,
    SUM(CASE WHEN status IN ('INTERVIEW','OFFERED') THEN 1 ELSE 0 END) AS enteredInterview,
    SUM(CASE WHEN status = 'OFFERED'                 THEN 1 ELSE 0 END) AS gotOffer,
    SUM(CASE WHEN status = 'INTERVIEW'                THEN 1 ELSE 0 END) AS stillInterviewCount,
    ROUND(
        SUM(CASE WHEN status = 'INTERVIEW' THEN 1 ELSE 0 END)
        * 100.0 / NULLIF(SUM(CASE WHEN status IN ('INTERVIEW','OFFERED') THEN 1 ELSE 0 END), 0),
        1
    ) AS churnRate
FROM job
WHERE user_id = #{userId}
  AND status IN ('INTERVIEW', 'OFFERED');


-- ============================================================================
-- 模块三：薪资区间分布统计
-- 说明：将 salary_range 字段解析后按预设区间分组，统计投递量与Offer量
-- 注：本统计直接基于原始文本格式计算，工程化场景建议先通过代码层解析为数值区间，再做统计分析
-- ============================================================================

-- 【薪资分布-完整SQL】按六个预设区间分组
-- label: 区间标签 | count: 投递量 | offerCount: 获得Offer数
-- 全量区间派生表 b 使用 LEFT JOIN 确保所有区间保留，缺失区间 COUNT 为 0
SELECT
    b.label AS label,
    COUNT(j.id) AS count,
    SUM(CASE WHEN j.status = 'OFFERED' THEN 1 ELSE 0 END) AS offerCount
FROM (
    SELECT '3k以下'    AS label, 0   AS low UNION ALL
    SELECT '3k~5k',    3   UNION ALL
    SELECT '5k~8k',    5   UNION ALL
    SELECT '8k~12k',   8   UNION ALL
    SELECT '12k~15k',  12  UNION ALL
    SELECT '15k以上',  15
) b
LEFT JOIN job j
    ON j.user_id = #{userId}
   AND (
       -- "Xk以下"格式：匹配"3k以下"等表述
       (LOWER(TRIM(j.salary_range)) REGEXP '以下' AND b.label = '3k以下')
       OR
       -- "Xk以上"格式：匹配"15k以上"等表述
       (LOWER(TRIM(j.salary_range)) REGEXP '以上' AND b.label = '15k以上')
       OR
       -- 标准区间格式：取左端点数值，换算为元后与区间下界比较
       (
           NOT LOWER(TRIM(j.salary_range)) REGEXP '以下|以上'
           AND CAST(
               SUBSTRING_INDEX(SUBSTRING_INDEX(LOWER(TRIM(j.salary_range)), '-', 1), '.', -1)
               AS DECIMAL(10,2)
           )
           * CASE
                 WHEN LOWER(TRIM(j.salary_range)) REGEXP '万' THEN 10000
                 WHEN LOWER(TRIM(j.salary_range)) REGEXP 'k'  THEN 1000
                 ELSE 1
             END
           >= b.low * 1000
           AND (
               b.label = '15k以上'
               OR CAST(
                   SUBSTRING_INDEX(SUBSTRING_INDEX(LOWER(TRIM(j.salary_range)), '-', 1), '.', -1)
                   AS DECIMAL(10,2)
               )
               * CASE
                     WHEN LOWER(TRIM(j.salary_range)) REGEXP '万' THEN 10000
                     WHEN LOWER(TRIM(j.salary_range)) REGEXP 'k'  THEN 1000
                     ELSE 1
                 END
               < (b.low + 1) * 1000
           )
       )
   )
GROUP BY b.label
ORDER BY b.low ASC;


-- ============================================================================
-- 模块四：月度投递趋势统计
-- 说明：按月聚合投递量、面试数、Offer数，用于折线图展示求职活跃度
-- ============================================================================

-- 【月度趋势-完整SQL】按月统计投递量、面试量、Offer数
-- month: YYYY-MM格式 | appliedCount: 当月投递总数 | interviewCount: 当月面试数 | offerCount: 当月Offer数
SELECT
    DATE_FORMAT(apply_date, '%Y-%m') AS month,
    COUNT(*)                          AS appliedCount,
    SUM(CASE WHEN status = 'INTERVIEW' THEN 1 ELSE 0 END) AS interviewCount,
    SUM(CASE WHEN status = 'OFFERED'   THEN 1 ELSE 0 END) AS offerCount
FROM job
WHERE user_id = #{userId}
GROUP BY DATE_FORMAT(apply_date, '%Y-%m')
ORDER BY month ASC;


-- ============================================================================
-- 模块五：城市维度分布分析
-- 说明：按 region 字段统计各城市投递量，用于地图/饼图展示地域偏好
-- ============================================================================

-- 【城市分布-完整SQL】按城市统计投递量，按数量降序排列
-- name: 城市名（TRIM后） | value: 该城市投递岗位数
SELECT
    TRIM(region)  AS name,
    COUNT(*)      AS value
FROM job
WHERE user_id = #{userId}
  AND region IS NOT NULL
  AND TRIM(region) != ''
GROUP BY TRIM(region)
ORDER BY value DESC;


-- ============================================================================
-- 附录：其他辅助统计查询
-- ============================================================================

-- 【平均响应天数】从投递日到首次非APPLIED状态的平均天数（反映求职反馈速度）
-- 返回 NULL 表示当前无有效数据（无面试/Offer/拒绝记录）
SELECT AVG(DATEDIFF(update_time, apply_date)) AS avgResponseDays
FROM job
WHERE user_id = #{userId}
  AND status != 'APPLIED'
  AND update_time > apply_date;

-- 【笔记类型分布】各类笔记（SKILL/INSIGHT）数量统计
-- type: 笔记类型 | count: 该类型笔记数
SELECT
    note_type AS type,
    COUNT(*)  AS count
FROM note
WHERE user_id = #{userId}
GROUP BY note_type
ORDER BY count DESC;
-- 说明：智能岗位匹配算法为代码层实现（多维度规则加权+薪资区间重叠度计算），详见docs/algorithm.md
