package com.game.nt.risk.internal;

import java.time.LocalDateTime;

/** 风控事件行（MyBatis 结果映射用可变 POJO）。 */
public class RiskEventRow {

    private long id;
    private Long userId;
    private String type;
    private String severity;
    private String detail;
    private String status;
    private LocalDateTime createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
