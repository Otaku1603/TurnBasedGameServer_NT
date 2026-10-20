package com.game.nt.identity.internal;

import com.game.nt.identity.IdentityApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/** 身份模块实现。 */
@Service
public class IdentityService implements IdentityApi {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public IdentityService(UserMapper userMapper, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AccountInfo register(String username, String rawPassword, String nickname) {
        if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用户名须为 3~32 位字母/数字/下划线");
        }
        if (rawPassword == null || rawPassword.length() < 6 || rawPassword.length() > 32) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "密码须为 6~32 位");
        }
        String nick = (nickname == null || nickname.isBlank()) ? username : nickname.trim();
        if (nick.length() > 16) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "昵称过长");
        }
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        // 与 LocalDataSeeder 相同的初始设定（账号 + 默认战士角色 + 角色技能）
        jdbc.update("""
                INSERT INTO t_user (username, nickname, password_hash, elo_rating, gold, win_count, total_battles, status)
                VALUES (?, ?, ?, 1000, 1000, 0, 0, 1)
                """, username, nick, passwordEncoder.encode(rawPassword));
        Long userId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE username = ?", Long.class, username);

        Map<String, Object> template = jdbc.queryForMap(
                "SELECT * FROM t_character_template WHERE char_type = ?", "warrior");
        jdbc.update("""
                INSERT INTO t_character
                    (user_id, char_name, char_type, max_hp, attack, defense, crit_rate, dodge_rate, is_active)
                VALUES (?, ?, 'warrior', ?, ?, ?, ?, ?, 1)
                """, userId, nick,
                template.get("base_hp"), template.get("base_attack"), template.get("base_defense"),
                template.get("base_crit_rate"), template.get("base_dodge_rate"));
        Long characterId = jdbc.queryForObject(
                "SELECT id FROM t_character WHERE user_id = ? AND is_active = 1", Long.class, userId);
        jdbc.queryForList("SELECT id FROM t_skill WHERE char_type = ? OR char_type = 'all'", Integer.class, "warrior")
                .forEach(skillId -> jdbc.update(
                        "INSERT IGNORE INTO t_character_skill (character_id, skill_id) VALUES (?, ?)",
                        characterId, skillId));

        return new AccountInfo(userId, username, nick, 1000, 1000L, 0, 0, false);
    }

    @Override
    public Optional<AccountInfo> findByUserId(long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(IdentityService::toInfo);
    }

    @Override
    public Optional<AccountInfo> authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return Optional.empty();
        }
        UserEntity user = userMapper.findByUsername(username);
        if (user == null) {
            return Optional.empty();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            return Optional.empty();
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Optional.empty();
        }
        return Optional.of(toInfo(user));
    }

    @Override
    @Transactional
    public boolean tryDeductGold(long userId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "扣减金额必须为正");
        }
        // 单条 UPDATE 完成校验+扣减，天然并发安全（无 read-modify-write 窗口）
        return userMapper.tryDeductGold(userId, amount) == 1;
    }

    @Override
    @Transactional
    public void addGold(long userId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "增加金额必须为正");
        }
        userMapper.addGold(userId, amount);
    }

    private static AccountInfo toInfo(UserEntity u) {
        return new AccountInfo(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getEloRating() == null ? 0 : u.getEloRating(),
                u.getGold() == null ? 0L : u.getGold(),
                u.getWinCount() == null ? 0 : u.getWinCount(),
                u.getTotalBattles() == null ? 0 : u.getTotalBattles(),
                u.getStatus() != null && u.getStatus() == 0
        );
    }
}
