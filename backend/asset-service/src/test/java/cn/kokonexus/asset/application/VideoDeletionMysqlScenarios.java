package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅由隔离MySQL检查调用；事务/SQL竞态不冒充真实对象删除，实际文件清理由浏览器用例另验。 */
final class VideoDeletionMysqlScenarios {

    static void run(JdbcTemplate jdbc, VideoDraftState videos, VideoDeletionState deletions, String legacy) {
        var source = videos.register(input(301));
        var sourceTicket = videos.beginSource(301, source.getId());
        expect(ResourceNotFoundException.class, () -> deletions.request(999, source.getId(), uuid()));
        String request = uuid();
        var waiting = deletions.request(301, source.getId(), request);
        check(waiting.getDeletionWaitReason().equals("WRITES_UNVERIFIED"), "In-flight source not fenced");
        check(deletions.claim() == null, "Cleanup ran before source PUT settled");
        check(videos.sourceStored(301, sourceTicket).getState().equals("DELETING"), "Late source restored queue");
        expect(IllegalStateException.class, () -> videos.beginSource(301, source.getId()));
        var cleanup = deletions.claim();
        check(cleanup != null, "Settled source could not be claimed");
        var wrong = new VideoDraft();
        wrong.setId(cleanup.getId());
        wrong.setDeleteToken(uuid());
        check(!deletions.complete(wrong), "Wrong cleanup token released quota");
        jdbc.update("UPDATE video_capacity SET draft_count=0 WHERE owner_id=301");
        expect(IllegalStateException.class, () -> deletions.complete(cleanup));
        check(
            videos.owned(301, source.getId()).getState().equals("DELETING") && count(jdbc, 0) == 1,
            "Partial counter failure did not roll back tombstone/global quota"
        );
        jdbc.update("UPDATE video_capacity SET draft_count=1 WHERE owner_id=301");
        // 下列complete是SQL调用方模拟的已核验事实；不声称真实存储IO。
        check(deletions.complete(cleanup) && !deletions.complete(cleanup), "Completion not idempotent");
        check(
            count(jdbc, 0) == 0 && count(jdbc, 301) == 0 && videos.owned(301).isEmpty(),
            "Verified tombstone did not release once"
        );
        check(
            deletions.request(301, source.getId(), request).getState().equals("DELETED"),
            "Original delete receipt lost"
        );
        expect(IllegalStateException.class, () -> deletions.request(301, source.getId(), uuid()));
        check(
            videos.register(source).getState().equals("DELETED") && count(jdbc, 0) == 0,
            "Original upload resurrected tombstone"
        );

        var output = videos.register(input(302));
        videos.sourceStored(302, videos.beginSource(302, output.getId()));
        var job = videos.claim();
        check(job != null && job.getId().equals(output.getId()), "Expected original job");
        var outputTicket = videos.beginOutput(job);
        deletions.request(302, job.getId(), uuid());
        check(deletions.claim() == null, "Cleanup ignored in-flight output");
        job.setOutputBytes(64L);
        job.setDurationMillis(1000L);
        job.setWidth(320);
        job.setHeight(180);
        check(!videos.outputStored(job, outputTicket), "Late output restored READY");
        check(
            deletions.claim() == null &&
                videos.owned(302, job.getId()).getDeletionWaitReason().equals("WAIT_PROCESSING"),
            "Active processor slot released too early"
        );
        videos.processed(job);
        for (int attempt = 1; attempt <= 5; attempt++) {
            var retry = deletions.claim();
            check(retry != null && retry.getDeleteAttempts() == attempt, "Wrong cleanup attempts");
            check(deletions.failed(retry), "Cleanup failure not recorded");
            jdbc.update(
                "UPDATE video_draft SET delete_next_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=? AND delete_attempts<5",
                job.getId()
            );
        }
        check(
            deletions.claim() == null &&
                count(jdbc, 302) == 1 &&
                videos.owned(302, job.getId()).getDeletionWaitReason().equals("CLEANUP_FAILED"),
            "Unverified cleanup exceeded retry limit or released quota"
        );

        var beforeOutput = videos.register(input(303));
        videos.sourceStored(303, videos.beginSource(303, beforeOutput.getId()));
        var fenced = videos.claim();
        check(fenced != null, "Missing fenced process");
        deletions.request(303, fenced.getId(), uuid());
        expect(IllegalStateException.class, () -> videos.beginOutput(fenced));
        check(deletions.claim() == null, "Processor lease ignored");
        jdbc.update("UPDATE video_draft SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", fenced.getId());
        check(
            deletions.complete(deletions.claim()),
            "Expired processor could not be cleaned after output admission fenced"
        );
        expect(IllegalStateException.class, () -> videos.beginOutput(fenced));

        var uncertain = videos.register(input(304));
        var ticket = videos.beginSource(304, uncertain.getId());
        videos.writeUncertain(ticket);
        deletions.request(304, uncertain.getId(), uuid());
        expect(IllegalStateException.class, () -> videos.sourceStored(304, ticket));
        jdbc.update("UPDATE video_draft SET created_at=UTC_TIMESTAMP(6)-INTERVAL 1 DAY WHERE id=?", uncertain.getId());
        check(deletions.claim() == null && count(jdbc, 304) == 1, "Unknown PUT expired into assumed absence");
        check(
            deletions.request(901, legacy, uuid()).getDeletionWaitReason().equals("LEGACY_WRITES_UNVERIFIED") &&
                deletions.claim() == null,
            "Legacy untracked write cleaned automatically"
        );

        var stale = videos.register(input(305));
        deletions.request(305, stale.getId(), uuid());
        var old = deletions.claim();
        jdbc.update(
            "UPDATE video_draft SET delete_lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
            stale.getId()
        );
        var fresh = deletions.claim();
        check(fresh != null && !old.getDeleteToken().equals(fresh.getDeleteToken()), "Cleanup lease not re-fenced");
        check(!deletions.complete(old) && !deletions.failed(old), "Stale cleaner changed current job");
        check(deletions.complete(fresh) && count(jdbc, 305) == 0, "Current cleaner did not complete");
    }

    private static int count(JdbcTemplate jdbc, long owner) {
        return jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=?", Integer.class, owner);
    }

    private static VideoDraft input(long owner) {
        var row = new VideoDraft();
        row.setId(uuid());
        row.setRequestId(uuid());
        row.setOwnerId(owner);
        row.setTitle("deletion scenario");
        row.setDescription("");
        row.setSourceBytes(64L);
        row.setSourceSha256("a".repeat(64));
        return row;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected rejection");
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Unexpected exception", failure);
        }
    }
}
