package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Deposit;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DepositRepository {

    private static final RowMapper<Deposit> DEPOSIT_MAPPER = (rs, rowNum) -> new Deposit(
            UUID.fromString(rs.getString("id")),
            rs.getString("user_id"),
            UUID.fromString(rs.getString("wallet_id")),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public DepositRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Same insert-as-the-idempotency-check pattern as {@code TransferRepository#insertPending}, for
     * the identical reason: the uniqueness check and "reserve this key" must be one atomic event, not
     * a separate check-then-insert. Called only after the wallet's row lock is already held (see
     * WalletRepository#lockSingle) — otherwise this INSERT's own implicit FK lock on wallets(id) would
     * reopen the exact deadlock hazard found and fixed for transfers.
     */
    public Optional<Deposit> insertPending(String userId, UUID walletId, long amountPaise,
                                            String idempotencyKey, String requestHash) {
        String sql = """
                INSERT INTO deposits (user_id, wallet_id, amount_paise, idempotency_key, request_hash)
                VALUES (:userId, :walletId, :amount, :key, :hash)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                RETURNING id, user_id, wallet_id, amount_paise, idempotency_key, request_hash, created_at
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("walletId", walletId)
                .addValue("amount", amountPaise)
                .addValue("key", idempotencyKey)
                .addValue("hash", requestHash);
        List<Deposit> rows = jdbc.query(sql, params, DEPOSIT_MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<Deposit> findByUserAndKey(String userId, String idempotencyKey) {
        String sql = """
                SELECT id, user_id, wallet_id, amount_paise, idempotency_key, request_hash, created_at
                FROM deposits
                WHERE user_id = :userId AND idempotency_key = :key
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("key", idempotencyKey);
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, params, DEPOSIT_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
