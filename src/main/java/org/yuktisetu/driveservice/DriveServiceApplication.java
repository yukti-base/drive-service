package org.yuktisetu.driveservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Student-facing drive operations: which drives a student may see, Easy Apply,
 * and the applicant pool the TnP works from.
 *
 * Job posting lives in admin-service; this service owns everything from
 * "students can see it" through "the deadline passed and the pool is sealed".
 *
 * @EnableScheduling is here for the deadline sweep -- the component itself is
 * property-gated so it does not run on a developer's laptop against the shared
 * database.
 *
 * @EnableAsync activates the shared executors from core's YuktiSetuAsyncConfig.
 * Without it, every @Async method in this service would run inline on the caller's
 * thread and the annotation would be a silent no-op.
 */
@SpringBootApplication
// Scoped to the contexts this service owns or reaches, instead of the old
// blanket org.yuktisetu.db. The blanket scan mapped every entity on the
// classpath, so one bad column broke startup in four services at once.
// These lists shrink on their own as cross-context dependencies are cut.
@EntityScan(basePackages = {"org.yuktisetu.drive.db", "org.yuktisetu.identity.db", "org.yuktisetu.institute.db", "org.yuktisetu.profile.db", "org.yuktisetu.messaging.db"})
@EnableJpaRepositories(basePackages = {"org.yuktisetu.drive.repository", "org.yuktisetu.identity.repository", "org.yuktisetu.institute.repository", "org.yuktisetu.profile.repository", "org.yuktisetu.messaging.repository"})
@ComponentScan(basePackages = {"org.yuktisetu.driveservice", "org.yuktisetu.core"})
@EnableMethodSecurity
@EnableScheduling
@EnableAsync
public class DriveServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriveServiceApplication.class, args);
    }
}
