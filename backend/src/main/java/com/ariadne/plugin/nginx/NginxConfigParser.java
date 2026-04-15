package com.ariadne.plugin.nginx;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class NginxConfigParser {

    private static final Pattern BLOCK_START_PATTERN = Pattern.compile("\\b(upstream|server)\\b([^;{]*)\\{");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?m)#.*$");
    private static final Pattern UPSTREAM_SERVER_PATTERN = Pattern.compile("(?m)^\\s*server\\s+([^;]+);");
    private static final Pattern SERVER_NAME_PATTERN = Pattern.compile("(?m)^\\s*server_name\\s+([^;]+);");
    private static final Pattern PROXY_PASS_PATTERN = Pattern.compile("(?m)^\\s*proxy_pass\\s+([^;]+);");
    private static final Pattern URI_PATTERN = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/]+)(/.*)?$");

    public ParsedNginxConfig parse(String rawConfig) {
        if (rawConfig == null || rawConfig.isBlank()) {
            return ParsedNginxConfig.empty();
        }

        var sanitized = COMMENT_PATTERN.matcher(rawConfig).replaceAll("");
        var blocks = extractBlocks(sanitized);

        var upstreams = new ArrayList<ParsedUpstream>();
        var upstreamNames = new LinkedHashSet<String>();
        for (var block : blocks) {
            if (!"upstream".equals(block.directive())) {
                continue;
            }
            var upstream = parseUpstream(block);
            upstreams.add(upstream);
            upstreamNames.add(upstream.name());
        }

        var serverNames = new LinkedHashSet<String>();
        var proxyPassTargets = new LinkedHashSet<String>();
        var proxyPasses = new ArrayList<ParsedProxyPass>();
        for (var block : blocks) {
            if (!"server".equals(block.directive())) {
                continue;
            }
            parseServerBlock(block.body(), upstreamNames, serverNames, proxyPassTargets, proxyPasses);
        }

        return new ParsedNginxConfig(
                List.copyOf(serverNames),
                List.copyOf(upstreamNames),
                List.copyOf(proxyPassTargets),
                upstreams,
                proxyPasses
        );
    }

    private List<ParsedBlock> extractBlocks(String content) {
        var blocks = new ArrayList<ParsedBlock>();
        var matcher = BLOCK_START_PATTERN.matcher(content);
        var searchFrom = 0;

        while (matcher.find(searchFrom)) {
            var directive = matcher.group(1).trim();
            var argument = matcher.group(2).trim();
            var braceIndex = matcher.end() - 1;
            var closingBraceIndex = findMatchingBrace(content, braceIndex);
            if (closingBraceIndex < 0) {
                break;
            }

            var body = content.substring(braceIndex + 1, closingBraceIndex);
            blocks.add(new ParsedBlock(directive, argument, body));
            searchFrom = closingBraceIndex + 1;
        }

        return blocks;
    }

    private int findMatchingBrace(String content, int openingBraceIndex) {
        var depth = 0;
        for (int index = openingBraceIndex; index < content.length(); index++) {
            var character = content.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private ParsedUpstream parseUpstream(ParsedBlock block) {
        var servers = new ArrayList<ParsedUpstreamServer>();
        var matcher = UPSTREAM_SERVER_PATTERN.matcher(block.body());
        while (matcher.find()) {
            var rawValue = matcher.group(1).trim();
            var segments = rawValue.split("\\s+");
            var endpoint = segments.length == 0 ? rawValue : segments[0];
            var parameters = segments.length <= 1
                    ? List.<String>of()
                    : List.of(segments).subList(1, segments.length);

            servers.add(new ParsedUpstreamServer(
                    rawValue,
                    endpoint,
                    parseHost(endpoint),
                    parsePort(endpoint),
                    parameters
            ));
        }

        return new ParsedUpstream(block.argument(), servers);
    }

    private void parseServerBlock(
            String body,
            Set<String> upstreamNames,
            Set<String> serverNames,
            Set<String> proxyPassTargets,
            List<ParsedProxyPass> proxyPasses
    ) {
        var serverNameMatcher = SERVER_NAME_PATTERN.matcher(body);
        while (serverNameMatcher.find()) {
            var rawValue = serverNameMatcher.group(1).trim();
            for (var token : rawValue.split("\\s+")) {
                if (!token.isBlank() && !"_".equals(token)) {
                    serverNames.add(token);
                }
            }
        }

        var proxyPassMatcher = PROXY_PASS_PATTERN.matcher(body);
        while (proxyPassMatcher.find()) {
            var rawValue = proxyPassMatcher.group(1).trim();
            proxyPassTargets.add(rawValue);
            proxyPasses.add(parseProxyPass(rawValue, upstreamNames));
        }
    }

    private ParsedProxyPass parseProxyPass(String rawValue, Set<String> upstreamNames) {
        var matcher = URI_PATTERN.matcher(rawValue);
        if (!matcher.matches()) {
            return new ParsedProxyPass(rawValue, null, null, null, null, null);
        }

        var scheme = matcher.group(1);
        var authority = matcher.group(2);
        var path = matcher.group(3);
        var host = parseHost(authority);
        var port = parsePort(authority);
        var upstreamName = host != null && upstreamNames.contains(host) ? host : null;

        return new ParsedProxyPass(rawValue, scheme, host, port, path, upstreamName);
    }

    private String parseHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        if (endpoint.startsWith("unix:")) {
            return endpoint;
        }
        var normalized = endpoint;
        if (normalized.startsWith("[")) {
            var closingBracket = normalized.indexOf(']');
            return closingBracket > 0 ? normalized.substring(1, closingBracket) : normalized;
        }
        var colonIndex = normalized.lastIndexOf(':');
        if (colonIndex > 0 && normalized.indexOf(':') == colonIndex) {
            return normalized.substring(0, colonIndex);
        }
        return normalized;
    }

    private Integer parsePort(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        if (endpoint.startsWith("[")) {
            var closingBracket = endpoint.indexOf(']');
            if (closingBracket >= 0 && closingBracket + 2 <= endpoint.length() && endpoint.charAt(closingBracket + 1) == ':') {
                return parseInteger(endpoint.substring(closingBracket + 2));
            }
            return null;
        }
        var colonIndex = endpoint.lastIndexOf(':');
        if (colonIndex > 0 && endpoint.indexOf(':') == colonIndex) {
            return parseInteger(endpoint.substring(colonIndex + 1));
        }
        return null;
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record ParsedBlock(String directive, String argument, String body) {
    }

    public record ParsedNginxConfig(
            List<String> serverNames,
            List<String> upstreamNames,
            List<String> proxyPassTargets,
            List<ParsedUpstream> upstreams,
            List<ParsedProxyPass> proxyPasses
    ) {
        public static ParsedNginxConfig empty() {
            return new ParsedNginxConfig(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ParsedUpstream(String name, List<ParsedUpstreamServer> servers) {
    }

    public record ParsedUpstreamServer(
            String rawValue,
            String endpoint,
            String host,
            Integer port,
            List<String> parameters
    ) {
    }

    public record ParsedProxyPass(
            String rawValue,
            String scheme,
            String host,
            Integer port,
            String path,
            String upstreamName
    ) {
    }
}
