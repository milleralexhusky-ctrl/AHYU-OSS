package com.cpa.gateway.core;

/**
 * 调试面总开关。
 *
 * 19 个 `/v1/debug/*` 端点（antigravity、credits、detect/*、fallback、persona、quick-parse、
 * raw-stream、relay-watch、selftest、stations、stations-sync、toolfix、wb-claim、wb-extra、
 * wb-models、wb-tasks）以前在生产包里全部注册着：既扩大攻击面，也让"这是发布版还是调试版"
 * 从外面看不出来。
 *
 * 现在做成编译期常量：发布包 false（Server 里一条短路，这些路由直接 404），
 * 需要调试时用 `./build.sh --debug` 构建，构建脚本会把这里改成 true。
 */
public final class Debug {

    /** 发布包：false。`./build.sh --debug` 会把它改成 true。 */
    public static final boolean ON = false;

    private Debug() {
    }
}
