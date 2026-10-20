package com.game.nt.shared.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等键服务 —— 「业务原子化」的基础设施。
 *
 * <p>用法（在同一个事务里）：
 * <pre>{@code
 * @Transactional
 * public void claim(long userId, long mailId) {
 *     if (!idempotency.tryAcquire("mail:claim:" + mailId)) {
 *         throw new BusinessException(ErrorCode.CONFLICT, "重复领取");
 *     }
 *     ... 业务处理 ...
 *     idempotency.markDone(key, "OK");
 * }
 * }</pre>
 *
 * <p>关键设计：{@link #tryAcquire} 使用 <b>INSERT IGNORE</b> 而非 INSERT，
 * 主键冲突时不抛异常，因此不会把外围事务标记为 rollback-only（这是很多实现的踩坑点）。
 * 记录与业务同事务：业务失败回滚 → 幂等键一并回滚 → 允许客户端重试。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyMapper mapper;

    public IdempotencyService(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 尝试获取幂等键。
     *
     * @return true=首次获取（可继续执行）；false=已存在（重复请求，应拒绝）
     */
    @Transactional
    public boolean tryAcquire(String key) {
        boolean acquired = mapper.tryInsert(key) == 1;
        if (!acquired) {
            log.warn("幂等键已存在，拒绝重复执行: key={}, status={}", key, mapper.findStatus(key));
        }
        return acquired;
    }

    /** 标记完成（可选，便于排查）。 */
    @Transactional
    public void markDone(String key, String resultRef) {
        mapper.markDone(key, resultRef);
    }

    /** 主动释放（补偿场景）。 */
    @Transactional
    public void release(String key) {
        mapper.release(key);
    }
}
