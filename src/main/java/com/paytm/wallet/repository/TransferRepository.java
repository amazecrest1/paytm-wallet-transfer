package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> TRANSFER_MAPPER = (rs, rowNum) -> new Transfer(
            UUID.fromString(rs.getString("id")),
            rs.getString("initiator_user_id"),
            UUID.fromString(rs.getString("from_wallet_id")),
            UUID.fromString(rs.getString("to_wallet_id")),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getString("decline_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gate 2 mechanism: the idempotency-key uniqueness check IS this insert. It is the first
     * statement of the same transaction that later performs the debit/credit, so "does this key
     * already own a transfer" and "reserve this key for a transfer" are the same atomic event —
     * there is no gap between them for a concurrent duplicate to exploit (the TOCTOU problem a
     * separate check-then-insert would have).
     * <p>
     * ON CONFLICT DO NOTHING means a losing caller gets zero rows back rather than an exception —
     * exception-driven control flow is not appropriate here since, under the 30-way idempotent
     * retry-storm this is designed for, "losing" is the *expected* outcome for 29 of 30 callers.
     * <p>
     * If a concurrent duplicate's INSERT arrives while the winner's transaction is still open
     * (uncommitted), Postgres blocks this statement on the unique index until the winner's
     * transaction resolves, then re-checks the conflict against the now-final row. That blocking is
     * what turns "identical concurrent requests" into "one real answer, everyone else waits for and
     * then reads it" instead of a race.
     */
    public Optional<Transfer> insertPending(String initiatorUserId, UUID from, UUID to, long amountPaise,
                                             String idempotencyKey, String requestHash) {
        String sql = """
                INSERT INTO transfers
                    (initiator_user_id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, request_hash, status)
                VALUES (:initiatorUserId, :from, :to, :amount, :key, :hash, 'PENDING')
                ON CONFLICT (initiator_user_id, idempotency_key) DO NOTHING
                RETURNING id, initiator_user_id, from_wallet_id, to_wallet_id, amount_paise,
                          idempotency_key, request_hash, status, decline_reason, created_at, updated_at
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("initiatorUserId", initiatorUserId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("amount", amountPaise)
                .addValue("key", idempotencyKey)
                .addValue("hash", requestHash);
        List<Transfer> rows = jdbc.query(sql, params, TRANSFER_MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<Transfer> findByInitiatorAndKey(String initiatorUserId, String idempotencyKey) {
        String sql = """
                SELECT id, initiator_user_id, from_wallet_id, to_wallet_id, amount_paise,
                       idempotency_key, request_hash, status, decline_reason, created_at, updated_at
                FROM transfers
                WHERE initiator_user_id = :initiatorUserId AND idempotency_key = :key
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("initiatorUserId", initiatorUserId)
                .addValue("key", idempotencyKey);
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, params, TRANSFER_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Transfer> findById(UUID id) {
        String sql = """
                SELECT id, initiator_user_id, from_wallet_id, to_wallet_id, amount_paise,
                       idempotency_key, request_hash, status, decline_reason, created_at, updated_at
                FROM transfers WHERE id = :id
                """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), TRANSFER_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void markCompleted(UUID transferId) {
        String sql = "UPDATE transfers SET status = 'COMPLETED', updated_at = now() WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource("id", transferId));
    }

    /** The decline itself still commits (idempotency row included) — a retry of this exact
     * request must replay the decline, not re-attempt the debit. */
    public void markDeclined(UUID transferId, String reason) {
        String sql = "UPDATE transfers SET status = 'DECLINED', decline_reason = :reason, updated_at = now() WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", transferId)
                .addValue("reason", reason);
        jdbc.update(sql, params);
    }
}
