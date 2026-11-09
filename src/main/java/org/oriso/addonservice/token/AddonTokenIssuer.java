package org.oriso.addonservice.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the short-lived token an add-on receives instead of the user's Keycloak token. Add-ons
 * verify it against {@code /service/addons/.well-known/jwks.json}.
 */
@Component
public class AddonTokenIssuer {

  private static final Logger log = LoggerFactory.getLogger(AddonTokenIssuer.class);

  private final AddonProperties.Token properties;
  private final Clock clock;
  private final RSAKey key;
  private final NimbusJwtEncoder encoder;

  public AddonTokenIssuer(AddonProperties properties, Clock clock) {
    this.properties = properties.token();
    this.clock = clock;
    this.key = loadOrGenerate(this.properties.privateKeyPem());
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
  }

  /** Role of the AddonService itself, in tokens for calls without a user (statistics). */
  public static final String SERVICE_ROLE = "addon-service";

  public String issue(String addon, Caller caller, Long agencyId) {
    return issue(addon, caller, agencyId, null, null);
  }

  public String issue(String addon, Caller caller, Long agencyId, Long sessionId, String askerId) {
    var claims =
        baseClaims(addon, caller.userId())
            .claim("roles", List.copyOf(caller.roles()))
            .claim("tenantId", caller.tenantId());
    if (caller.username() != null) {
      claims.claim("username", caller.username());
    }
    if (agencyId != null) {
      claims.claim("agencyId", agencyId);
    }
    if (sessionId != null) {
      claims.claim("sessionId", sessionId);
    }
    if (askerId != null) {
      claims.claim("askerId", askerId);
    }
    return sign(claims);
  }

  /** Token for the AddonService's own calls to an add-on, scoped to one Träger. */
  public String issueForService(String addon, long tenantId) {
    return sign(baseClaims(addon, "addonservice").claim("roles", List.of(SERVICE_ROLE)).claim("tenantId", tenantId));
  }

  public JwtDecoder decoder() {
    try {
      var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
      return decoder;
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private JwtClaimsSet.Builder baseClaims(String addon, String subject) {
    Instant now = clock.instant();
    return JwtClaimsSet.builder()
        .issuer(properties.issuer())
        .audience(List.of(addon))
        .subject(subject)
        .issuedAt(now)
        .expiresAt(now.plus(properties.ttl()));
  }

  private String sign(JwtClaimsSet.Builder claims) {
    var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).type("JWT").build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  public Map<String, Object> publicJwks() {
    return new JWKSet(key.toPublicJWK()).toJSONObject();
  }

  private static RSAKey loadOrGenerate(String pem) {
    try {
      if (pem == null || pem.isBlank()) {
        log.warn(
            "addons.token.private-key-pem is not set: generating a key for this instance only. "
                + "Tokens become invalid on restart and differ between replicas.");
        return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate();
      }
      String body =
          pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
      KeyFactory factory = KeyFactory.getInstance("RSA");
      var privateKey =
          (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
      var publicKey =
          (RSAPublicKey)
              factory.generatePublic(
                  new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
      return new RSAKey.Builder(publicKey)
          .privateKey(privateKey)
          .keyUse(KeyUse.SIGNATURE)
          .keyIDFromThumbprint()
          .build();
    } catch (JOSEException | java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid addons.token.private-key-pem (expected PKCS#8 RSA)", e);
    }
  }
}
