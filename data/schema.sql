USE job_notes;

-- 岗位投递主表
DROP TABLE IF EXISTS job;
CREATE TABLE job (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL DEFAULT 1,
    company_name VARCHAR(100) COMMENT '公司名称',
    position_name VARCHAR(100) COMMENT '岗位名称',
    job_link VARCHAR(255) COMMENT '岗位链接',
    salary_range VARCHAR(50) COMMENT '薪资区间',
    region VARCHAR(50) COMMENT '投递地区',
    apply_date DATE COMMENT '投递日期',
    status VARCHAR(20) COMMENT '投递状态',
    remark VARCHAR(500) COMMENT '备注',
    min_education VARCHAR(20) COMMENT '最低学历要求',
    required_skill VARCHAR(200) COMMENT '技能要求',
    create_time DATETIME DEFAULT NOW() COMMENT '创建时间',
    update_time DATETIME DEFAULT NOW() COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 面试/备考笔记表
DROP TABLE IF EXISTS note;
CREATE TABLE note (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    job_id INT NOT NULL,
    note_type VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
