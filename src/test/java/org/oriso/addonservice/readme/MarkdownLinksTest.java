package org.oriso.addonservice.readme;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class MarkdownLinksTest {

  private static final URI BASE = URI.create("https://raw.githubusercontent.com/org/repo/main/docs/README.md");
  private static final String DOCS = "https://raw.githubusercontent.com/org/repo/main/docs/";

  @Test
  void makesRelativeImagesAndLinksAbsolute() {
    String markdown =
        """
        # Tools
        ![Screenshot](img/a.png "Title") and [more](other.md#setup), [up](../LICENSE)
        ![abs](/org/repo/main/x.png) <img src="img/b.png" width="200">
        [ref]: img/c.png
        """;
    assertThat(MarkdownLinks.absolutize(markdown, BASE))
        .contains("![Screenshot](" + DOCS + "img/a.png \"Title\")")
        .contains("[more](" + DOCS + "other.md#setup)")
        .contains("[up](https://raw.githubusercontent.com/org/repo/main/LICENSE)")
        .contains("![abs](https://raw.githubusercontent.com/org/repo/main/x.png)")
        .contains("<img src=\"" + DOCS + "img/b.png\" width=\"200\">")
        .contains("[ref]: " + DOCS + "img/c.png");
  }

  @Test
  void leavesAbsoluteTargetsAnchorsAndCodeBlocksAlone() {
    String markdown =
        """
        [site](https://example.org/a.png) [mail](mailto:a@b.de) [top](#tools)
        ```
        ![code](img/a.png)
        ```
        """;
    assertThat(MarkdownLinks.absolutize(markdown, BASE)).isEqualTo(markdown);
  }
}
