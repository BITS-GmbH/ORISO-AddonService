package org.oriso.addonservice.readme;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes relative link and image targets of a Markdown text absolute against the URL it came from,
 * so the browser loads images directly from the add-on's host. Fenced code blocks stay as they are.
 */
public final class MarkdownLinks {

  private static final Pattern INLINE = Pattern.compile("(!?\\[[^\\]\\n]*\\]\\(\\s*<?)([^)\\s>]+)");
  private static final Pattern REFERENCE = Pattern.compile("^( {0,3}\\[[^\\]]+\\]:\\s*<?)(\\S+?)(?=>|\\s|$)");
  private static final Pattern HTML = Pattern.compile("(<(?:img|a|source)\\b[^>]*?\\b(?:src|href)\\s*=\\s*\")([^\"]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");

  private MarkdownLinks() {}

  public static String absolutize(String markdown, URI base) {
    var out = new StringBuilder(markdown.length());
    String fence = null;
    for (String line : markdown.split("(?<=\n)", -1)) {
      Matcher fenceMatch = FENCE.matcher(line);
      if (fenceMatch.find()) {
        if (fence == null) {
          fence = fenceMatch.group(1);
        } else if (fence.equals(fenceMatch.group(1))) {
          fence = null;
        }
        out.append(line);
      } else if (fence != null) {
        out.append(line);
      } else {
        out.append(rewrite(rewrite(rewrite(line, INLINE, base), REFERENCE, base), HTML, base));
      }
    }
    return out.toString();
  }

  private static String rewrite(String line, Pattern pattern, URI base) {
    return pattern.matcher(line).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + resolve(match.group(2), base)));
  }

  private static String resolve(String target, URI base) {
    if (target.startsWith("#") || SCHEME.matcher(target).find()) {
      return target;
    }
    try {
      return base.resolve(target).toString();
    } catch (IllegalArgumentException e) {
      return target;
    }
  }
}
