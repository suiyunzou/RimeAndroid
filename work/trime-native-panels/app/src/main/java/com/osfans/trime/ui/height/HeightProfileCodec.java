package com.osfans.trime.ui.height;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The dedicated height profile is also consumed by the theme's Lua controller. */
final class HeightProfileCodec {
    private HeightProfileCodec() {}

    private static void validate(int portrait, int landscape) throws IOException {
        if (portrait < 144 || portrait > 320 || landscape < 128 || landscape > 248) {
            throw new IOException("高度超出允许范围");
        }
    }

    static int[] parse(byte[] bytes) throws IOException {
        String header = new String(bytes, StandardCharsets.UTF_8).split("\n", 2)[0].trim();
        Matcher match = Pattern.compile("# trime-height-dp: (\\d{3}) (\\d{3})").matcher(header);
        if (!match.matches()) throw new IOException("不是高度配置文件");
        int portrait = Integer.parseInt(match.group(1));
        int landscape = Integer.parseInt(match.group(2));
        validate(portrait, landscape);
        return new int[] {portrait, landscape};
    }

    static byte[] build(int portrait, int landscape, boolean wide, InputStream fields) throws IOException {
        validate(portrait, landscape);
        LinkedHashMap<String, LinkedHashMap<String, Integer>> groups = new LinkedHashMap<>();
        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fields, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 3) throw new IOException("高度字段格式有误");
                boolean isPortrait = parts[0].equals("p");
                if (!isPortrait && !parts[0].equals("l")) throw new IOException("高度方向无效");
                String field = isPortrait ? "keyboard_height" : "keyboard_height_land";
                if (!parts[1].matches("(?:style|preset_keyboards/[a-zA-Z0-9_.-]+)/" + field)) {
                    throw new IOException("高度字段无效");
                }
                int original;
                try { original = Integer.parseInt(parts[2]); }
                catch (NumberFormatException error) { throw new IOException("高度基准无效", error); }
                if (original <= 0 || original > 10000) throw new IOException("高度基准无效");
                int base = isPortrait ? 200 : 176;
                int actual = isPortrait ? portrait : landscape;
                rows++;
                if (actual == base) continue;
                String group = parts[1].substring(0, parts[1].lastIndexOf('/'));
                if (!groups.containsKey(group)) groups.put(group, new LinkedHashMap<>());
                groups.get(group).put(field, (original * actual + base / 2) / base);
            }
        }
        if (rows == 0) throw new IOException("没有高度字段");
        StringBuilder out = new StringBuilder("# trime-height-dp: ").append(portrait).append(' ').append(landscape)
                .append("\n# trime-height-orientation: ").append(wide ? 'l' : 'p').append('\n');
        if (groups.containsKey("style")) {
            out.append("style:\n");
            for (Map.Entry<String, Integer> item : groups.remove("style").entrySet()) {
                out.append("  ").append(item.getKey()).append(": ").append(item.getValue()).append('\n');
            }
        } else out.append("style: {}\n");
        if (groups.isEmpty()) out.append("boards: {}\n");
        else {
            out.append("boards:\n");
            for (Map.Entry<String, LinkedHashMap<String, Integer>> group : groups.entrySet()) {
                out.append("  \"").append(group.getKey().substring("preset_keyboards/".length())).append("\":\n");
                for (Map.Entry<String, Integer> item : group.getValue().entrySet()) {
                    out.append("    ").append(item.getKey()).append(": ").append(item.getValue()).append('\n');
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
