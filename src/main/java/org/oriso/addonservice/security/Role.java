package org.oriso.addonservice.security;

/** Keycloak realm roles used by ORISO. */
public final class Role {

  public static final String ASKER = "user";
  public static final String CONSULTANT = "consultant";
  public static final String TENANT_ADMIN = "tenant-admin";
  public static final String SINGLE_TENANT_ADMIN = "single-tenant-admin";
  public static final String AGENCY_ADMIN = "agency-admin";
  public static final String RESTRICTED_AGENCY_ADMIN = "restricted-agency-admin";

  private Role() {}
}
