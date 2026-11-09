package org.oriso.addonservice.web;

import java.util.Map;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

  private final AddonTokenIssuer issuer;

  public JwksController(AddonTokenIssuer issuer) {
    this.issuer = issuer;
  }

  @GetMapping("/addons/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return issuer.publicJwks();
  }
}
