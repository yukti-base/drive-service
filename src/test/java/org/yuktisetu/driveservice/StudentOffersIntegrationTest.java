package org.yuktisetu.driveservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.driveservice.dto.StudentOfferResponses.MyOffer;
import org.yuktisetu.driveservice.service.StudentOfferService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A student's answer to an offer (decided 2026-11-01): accept, decline with a
 * reason, and ask to withdraw an accepted offer with a reason. Not @Transactional;
 * runs against yuktisetu_test.
 */
@SpringBootTest
@ActiveProfiles("test")
class StudentOffersIntegrationTest {

    @Autowired StudentOfferService offers;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicLong SEQ = new AtomicLong(970000);

    private long collegeId;
    private long companyId;
    private long postingId;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into trusts (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'PCET', 'ACTIVE')", "T" + tag);
        long trust = jdbc.queryForObject("select max(id) from trusts", Long.class);
        jdbc.update("insert into colleges (created_at, updated_at, is_deleted, code, name, primary_contact_email, primary_contact_name, status, trust_id) "
                + "values (now(), now(), false, ?, ?, 'c@x.test', 'C', 'ACTIVE', ?)", "C" + tag, "College " + tag, trust);
        collegeId = jdbc.queryForObject("select max(id) from colleges", Long.class);
        jdbc.update("insert into companies (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'Acme', 'ACTIVE')", "CO" + tag);
        companyId = jdbc.queryForObject("select max(id) from companies", Long.class);
        jdbc.update("""
                insert into job_postings (created_at, updated_at, is_deleted, academic_year, application_deadline,
                       ctc_currency, job_type, marks_student_placed, notify_on_publish, package_basis, posting_code,
                       ppo_possible, shortlisting_mode, status, title, version, company_id, ctc_max, reopen_count)
                values (now(), now(), false, '2027', now() - interval '1 day', 'INR', 'INTERNSHIP', true, false,
                        'CTC', ?, false, 'MANUAL', 'COMPLETED', 'SDE Intern', 0, ?, 1200000, 0)
                """, "P" + tag, companyId);
        postingId = jdbc.queryForObject("select max(id) from job_postings", Long.class);
    }

    private long student() {
        long n = SEQ.incrementAndGet();
        jdbc.update("insert into users (email, phone, password, first_name, last_name, status, created_at, updated_at, is_deleted) "
                + "values (?, ?, 'x', 'Stu', 'T', 'ACTIVE', now(), now(), false)", "offer" + n + "@x.test", "9" + String.format("%09d", n));
        return jdbc.queryForObject("select id from users where email = ?", Long.class, "offer" + n + "@x.test");
    }

    /** {applicationId, offerId} for a live INTERNSHIP offer held by this student. */
    private long[] offer(long student) {
        jdbc.update("insert into job_applications (created_at, updated_at, is_deleted, applied_at, source, status, student_user_id, posting_id) "
                + "values (now(), now(), false, now(), 'EASY_APPLY', 'OFFERED', ?, ?)", student, postingId);
        long app = jdbc.queryForObject("select max(id) from job_applications", Long.class);
        jdbc.update("""
                insert into placement_offers (created_at, updated_at, is_deleted, counts_toward_placement, ctc_currency, ctc_amount,
                       offer_date, offer_type, package_basis, source, status, student_user_id, college_id, company_id,
                       posting_id, application_id, active_internship_lock)
                values (now(), now(), false, true, 'INR', 1200000, current_date, 'INTERNSHIP', 'CTC', 'PORTAL', 'OFFERED',
                        ?, ?, ?, ?, ?, ?)
                """, student, collegeId, companyId, postingId, app, student);
        return new long[]{app, jdbc.queryForObject("select max(id) from placement_offers", Long.class)};
    }

    private String status(String table, long id) {
        return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    @Test
    @DisplayName("accepting moves the offer and the application, with history")
    void accept() {
        long s = student();
        long[] o = offer(s);

        MyOffer after = offers.accept(s, o[1]);

        assertEquals("ACCEPTED", after.status());
        assertEquals("OFFER_ACCEPTED", status("job_applications", o[0]));
        assertTrue(after.canRequestWithdrawal());
        assertFalse(after.canDecline(), "an accepted offer is given up by withdrawal, not by declining");
        assertEquals(1, jdbc.queryForObject("select count(*) from application_status_history where application_id = ? and to_status = 'OFFER_ACCEPTED' and actor_kind = 'STUDENT'",
                Integer.class, o[0]));
    }

    @Test
    @DisplayName("declining needs a reason, frees the internship lock, and cannot be done twice")
    void declineWithReason() {
        long s = student();
        long[] o = offer(s);

        assertThrows(ApiException.class, () -> offers.decline(s, o[1], "  "));
        assertEquals("OFFERED", status("placement_offers", o[1]), "no reason, no change");

        offers.decline(s, o[1], "Accepted a higher-paying offer elsewhere");

        assertEquals("DECLINED", status("placement_offers", o[1]));
        assertEquals("OFFER_DECLINED", status("job_applications", o[0]));
        assertNull(jdbc.queryForObject("select active_internship_lock from placement_offers where id = ?", Long.class, o[1]));
        assertEquals("Accepted a higher-paying offer elsewhere",
                jdbc.queryForObject("select decline_reason from placement_offers where id = ?", String.class, o[1]));

        ApiException again = assertThrows(ApiException.class, () -> offers.accept(s, o[1]));
        assertEquals("OFFER_NOT_OPEN", again.getCode());
    }

    @Test
    @DisplayName("a withdrawal request leaves the offer accepted and cannot be filed twice")
    void withdrawalRequest() {
        long s = student();
        long[] o = offer(s);
        ApiException early = assertThrows(ApiException.class, () -> offers.requestWithdrawal(s, o[1], "Changed my mind"));
        assertEquals("OFFER_NOT_ACCEPTED", early.getCode(), "before accepting, a student declines instead");

        offers.accept(s, o[1]);
        MyOffer after = offers.requestWithdrawal(s, o[1], "Going for higher studies");

        assertEquals("ACCEPTED", after.status(), "the offer stands until the TnP decides");
        assertEquals("PENDING", after.withdrawalRequest().status());
        assertFalse(after.canRequestWithdrawal());
        ApiException dup = assertThrows(ApiException.class, () -> offers.requestWithdrawal(s, o[1], "Going for higher studies"));
        assertEquals("WITHDRAWAL_PENDING", dup.getCode());
    }

    @Test
    @DisplayName("another student's offer looks like it does not exist")
    void cannotTouchSomeoneElsesOffer() {
        long owner = student();
        long other = student();
        long[] o = offer(owner);

        ApiException e = assertThrows(ApiException.class, () -> offers.accept(other, o[1]));
        assertEquals("OFFER_NOT_FOUND", e.getCode());
        assertEquals("OFFERED", status("placement_offers", o[1]));
    }

    @Test
    @DisplayName("accept and decline racing on the same offer: exactly one wins")
    void acceptDeclineRace() throws Exception {
        for (int round = 0; round < 10; round++) {
            long s = student();
            long[] o = offer(s);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> accept = () -> { try { offers.accept(s, o[1]); return true; } catch (ApiException e) { return false; } };
                Callable<Boolean> decline = () -> { try { offers.decline(s, o[1], "Not interested any more"); return true; } catch (ApiException e) { return false; } };
                List<Future<Boolean>> f = pool.invokeAll(List.of(accept, decline));
                int wins = (f.get(0).get() ? 1 : 0) + (f.get(1).get() ? 1 : 0);
                assertEquals(1, wins, "round " + round);
                String offerStatus = status("placement_offers", o[1]);
                String appStatus = status("job_applications", o[0]);
                assertTrue(("ACCEPTED".equals(offerStatus) && "OFFER_ACCEPTED".equals(appStatus))
                        || ("DECLINED".equals(offerStatus) && "OFFER_DECLINED".equals(appStatus)),
                        "offer and application agree: " + offerStatus + " / " + appStatus);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
