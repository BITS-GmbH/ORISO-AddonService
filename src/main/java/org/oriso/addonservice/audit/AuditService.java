package org.oriso.addonservice.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.oriso.addonservice.host.HostToken;
import org.oriso.addonservice.host.LocalizedText;
import org.oriso.addonservice.host.NotificationService;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Audit entries add-ons write about what their users did; readable for admins in ORISO-Admin. */
@Service
public class AuditService {

  public static final String SUBJECT_SELF = "self";
  public static final String SUBJECT_ASKER = "asker";

  private static final Pattern ACTION = Pattern.compile("^[a-z0-9._-]{1,64}$");
  private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};
  private static final TypeReference<List<String>> ROLES = new TypeReference<>() {};

  private final AddonAuditEntryRepository repository;
  private final JsonMapper json;
  private final Clock clock;

  @PersistenceContext private EntityManager entityManager;

  public AuditService(AddonAuditEntryRepository repository, JsonMapper json, Clock clock) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public long record(RegisteredAddon addon, HostToken token, AuditRequest request) {
    long tenantId = token.requireTenant();
    var problems = new ArrayList<String>();
    if (request.action() == null || !ACTION.matcher(request.action()).matches()) {
      problems.add("action must match " + ACTION.pattern());
    }
    problems.addAll(LocalizedText.problems("summary", request.summary(), 300));
    if (request.subject() != null && !SUBJECT_SELF.equals(request.subject()) && !SUBJECT_ASKER.equals(request.subject())) {
      problems.add("subject must be 'self', 'asker' or omitted");
    }
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", problems));
    }
    String appId = request.appId() == null ? null : NotificationService.requireApp(addon, request.appId()).id();
    var entry =
        new AddonAuditEntry(
            clock.instant(),
            addon.name(),
            appId,
            request.action(),
            json.writeValueAsString(request.summary()),
            token.userId(),
            token.username(),
            json.writeValueAsString(token.roles()),
            tenantId,
            token.agencyId(),
            token.sessionId(),
            subject(token, request.subject()));
    return repository.save(entry).getId();
  }

  @Transactional(readOnly = true)
  public Page search(Query query) {
    if (query.agencies() != null && query.agencies().isEmpty()) {
      return new Page(List.of(), query.page(), query.size(), 0);
    }
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();

    var count = cb.createQuery(Long.class);
    var countRoot = count.from(AddonAuditEntry.class);
    count.select(cb.count(countRoot)).where(filters(cb, countRoot, query));
    long total = entityManager.createQuery(count).getSingleResult();

    var select = cb.createQuery(AddonAuditEntry.class);
    var root = select.from(AddonAuditEntry.class);
    select
        .select(root)
        .where(filters(cb, root, query))
        .orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
    List<Item> items =
        entityManager
            .createQuery(select)
            .setFirstResult(query.page() * query.size())
            .setMaxResults(query.size())
            .getResultList()
            .stream()
            .map(this::toItem)
            .toList();
    return new Page(items, query.page(), query.size(), total);
  }

  private static Predicate[] filters(CriteriaBuilder cb, Root<AddonAuditEntry> root, Query query) {
    var predicates = new ArrayList<Predicate>();
    if (query.tenantId() != null) {
      predicates.add(cb.equal(root.get("tenantId"), query.tenantId()));
    }
    if (query.agencies() != null) {
      predicates.add(root.get("agencyId").in(query.agencies()));
    }
    if (query.addon() != null) {
      predicates.add(cb.equal(root.get("addonName"), query.addon()));
    }
    if (query.from() != null) {
      predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
    }
    if (query.to() != null) {
      predicates.add(cb.lessThan(root.get("createdAt"), query.to()));
    }
    return predicates.toArray(Predicate[]::new);
  }

  private static String subject(HostToken token, String subject) {
    if (subject == null) {
      return null;
    }
    if (SUBJECT_SELF.equals(subject)) {
      return token.userId();
    }
    if (token.askerId() == null || token.askerId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token has no client");
    }
    return token.askerId();
  }

  private Item toItem(AddonAuditEntry entry) {
    List<String> roles = entry.getActorRolesJson() == null ? List.of() : json.readValue(entry.getActorRolesJson(), ROLES);
    return new Item(
        entry.getId(),
        entry.getCreatedAt(),
        entry.getAddonName(),
        entry.getAppId(),
        entry.getAction(),
        json.readValue(entry.getSummaryJson(), TEXT),
        new Actor(entry.getActorId(), entry.getActorUsername(), roles),
        entry.getTenantId(),
        entry.getAgencyId(),
        entry.getSessionId(),
        entry.getSubjectId());
  }

  public record AuditRequest(String action, Map<String, String> summary, String subject, String appId) {}

  /**
   * @param tenantId null for all Träger
   * @param agencies null for all agencies, otherwise only entries of these agencies
   */
  public record Query(
      Long tenantId, Set<Long> agencies, String addon, Instant from, Instant to, int page, int size) {}

  public record Actor(String id, String username, List<String> roles) {}

  public record Item(
      long id,
      Instant createdAt,
      String addon,
      String appId,
      String action,
      Map<String, String> summary,
      Actor actor,
      long tenantId,
      Long agencyId,
      Long sessionId,
      String subjectId) {}

  public record Page(List<Item> items, int page, int size, long total) {}
}
