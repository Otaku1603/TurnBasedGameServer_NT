package com.game.nt.rank.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
// Spring Boot 4 / Spring Framework 7 默认使用 Jackson 3，包名为 tools.jackson.*
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜服务。
 *
 * <p>策略：查询下推 SQL + Redis 短 TTL 缓存（榜单是典型"读多写少"场景）。
 * 缓存失败（Redis 不可用）时静默降级为直查数据库，不影响可用性。
 */
@Service
public class RankService {

    private static final Logger log = LoggerFactory.getLogger(RankService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    private static final int MAX_LIMIT = 100;

    /** gold 仅金榜填充；Elo 榜为 null（按 type 语义区分）。 */
    public record RankEntry(int rank, long userId, String nickname, Integer eloRating,
                            Long gold, int winCount, int totalBattles) {
    }

    public record RankBoard(String type, List<RankEntry> entries) {
    }

    private final RankMapper rankMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RankService(RankMapper rankMapper, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.rankMapper = rankMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public RankBoard topElo(int limit) {
        int safe = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return cacheOrCompute("elo", safe, () -> rankMapper.topByElo(safe, 0));
    }

    public RankBoard topGold(int limit) {
        int safe = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return cacheOrCompute("gold", safe, () -> rankMapper.topByGold(safe, 0));
    }

    /** 我的排名（不走缓存，实时性要求高）。 */
    public int myEloRank(long userId) {
        return rankMapper.myEloRank(userId);
    }

    private RankBoard cacheOrCompute(String type, int limit, java.util.function.Supplier<List<RankRow>> loader) {
        String key = "cache:rank:" + type + ":" + limit;
        String cached = getQuietly(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, RankBoard.class);
            } catch (Exception ex) {
                log.warn("排行榜缓存反序列化失败，回退查库: {}", ex.getMessage());
            }
        }

        List<RankRow> rows = loader.get();
        List<RankEntry> entries = new ArrayList<>(rows.size());
        int rank = 1;
        for (RankRow row : rows) {
            boolean goldBoard = "gold".equals(type);
            Integer eloVal = goldBoard ? null : Integer.valueOf(nz(row.getEloRating()));
            Long goldVal = goldBoard ? Long.valueOf(nz(row.getGold())) : null;
            entries.add(new RankEntry(rank++, row.getUserId(), row.getNickname(),
                    eloVal, goldVal, nz(row.getWinCount()), nz(row.getTotalBattles())));
        }
        RankBoard board = new RankBoard(type, entries);

        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(board), CACHE_TTL);
        } catch (Exception ex) {
            log.debug("排行榜写缓存失败（已降级）: {}", ex.getMessage());
        }
        return board;
    }

    private String getQuietly(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception ex) {
            log.debug("排行榜读缓存失败（已降级）: {}", ex.getMessage());
            return null;
        }
    }

    private static int nz(Number value) {
        return value == null ? 0 : value.intValue();
    }
}
