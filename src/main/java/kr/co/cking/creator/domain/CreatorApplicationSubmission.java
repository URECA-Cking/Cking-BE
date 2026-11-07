package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "creator_application_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorApplicationSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "version", nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_json", nullable = false, columnDefinition = "json")
    private CreatorApplicationForm form;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    public CreatorApplicationSubmission(Long applicationId, int version, CreatorApplicationForm form) {
        this.applicationId = applicationId;
        this.version = version;
        this.form = form;
        this.submittedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
