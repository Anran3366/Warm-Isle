package com.qqbot.plugin.util;

/**
 * 够用的 semver 比较：逐段比数字，预发布标识按字典序。
 *
 * <p>不引依赖（宿主要保持单 jar、零新增运行时库），也不追求完整 semver 语义：
 * 插件版本号实际就是 {@code 1.2.3} 这种形态，比较够用即可。
 */
public final class Versions {

    private Versions() {
    }

    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String[] pa = a.split("[.+-]");
        String[] pb = b.split("[.+-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int c;
            try {
                c = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
            } catch (NumberFormatException e) {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
