package com.vision.batch.camaroduplicate.notify;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties;
import com.vision.batch.camaroduplicate.model.ExecutionSummary;
import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.file.Path;
import java.util.Map;

/**
 * Plain-text mail with the CSV attached.
 * TODO(vision): if Vision already has a mail service / templates, delegate to it
 * and delete this class; keep the subject/body rules.
 * TODO(business): final wording and recipients.
 */
public class MailReportNotifier implements ReportNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailReportNotifier.class);

    private final JavaMailSender mailSender;
    private final CamaroDuplicateProperties.Mail config;

    public MailReportNotifier(JavaMailSender mailSender, CamaroDuplicateProperties.Mail config) {
        this.mailSender = mailSender;
        this.config = config;
    }

    @Override
    public void notify(ExecutionSummary summary, Path report, String reportError) {
        if (!config.isEnabled()) {
            log.info("Mail disabled, not sending report for instance {}", summary.jobInstanceId());
            return;
        }
        if (config.getTo().isEmpty()) {
            throw new IllegalStateException("No mail recipient configured (vision.batch.camaro-duplicate.mail.to)");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, report != null, "UTF-8");
            helper.setFrom(config.getFrom());
            helper.setTo(config.getTo().toArray(String[]::new));
            if (!config.getCc().isEmpty()) {
                helper.setCc(config.getCc().toArray(String[]::new));
            }
            helper.setSubject(subject(summary));
            helper.setText(body(summary, reportError), false);
            if (report != null) {
                helper.addAttachment(report.getFileName().toString(), new FileSystemResource(report));
            }
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Cannot build report mail", e);
        }
    }

    String subject(ExecutionSummary s) {
        String label = switch (s.status()) {
            case SUCCESS -> "OK";
            case PARTIAL_SUCCESS -> "PARTIAL - action required";
            case FAILED -> "FAILED";
        };
        return "%s Camaro duplicates %s - %s - %s".formatted(
                config.getSubjectPrefix(), s.fileType(), label, s.inputFileName());
    }

    String body(ExecutionSummary s, String reportError) {
        StringBuilder b = new StringBuilder();
        b.append("Camaro duplicate processing (flow #06 Fusion Tiers T tiers)\n\n");
        b.append("Status          : ").append(s.status()).append('\n');
        b.append("File            : ").append(s.inputFileName()).append(" (").append(s.fileType()).append(")\n");
        b.append("Job instance    : ").append(s.jobInstanceId())
                .append(" / execution ").append(s.jobExecutionId()).append('\n');
        b.append("Lines processed : ").append(s.total()).append("\n\n");
        for (Map.Entry<ProcessingOutcome, Long> e : s.countsByOutcome().entrySet()) {
            b.append("  ").append(String.format("%-24s", e.getKey())).append(e.getValue()).append('\n');
        }
        if (s.status() == ExecutionSummary.Status.FAILED) {
            b.append("\nThe job did not complete: the counts above only cover lines processed before the failure.\n")
             .append("The input file was left in place; the next run restarts from the last committed chunk.\n");
        }
        if (s.countsByOutcome().containsKey(ProcessingOutcome.ACTION_NOT_CONFIGURED)) {
            b.append("\nWARNING: no business action is configured, the Vision database was NOT modified.\n");
        }
        if (reportError != null) {
            b.append("\nThe CSV report could not be generated: ").append(reportError).append('\n')
             .append("Database processing results are stored and the report can be regenerated.\n");
        }
        return b.toString();
    }
}
