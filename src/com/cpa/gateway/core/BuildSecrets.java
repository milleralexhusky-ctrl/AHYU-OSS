package com.cpa.gateway.core;

/**
 * 构建期注入的凭据（4.50.2）。
 *
 * 这个文件在公库和私库里都存在，但内容不同：
 *  · 公库：保持空串，`Antigravity` 会退回占位提示，登录不可用；
 *  · 私库：`build.sh` 从 `.private/signing.env` 读取
 *    `ANTIGRAVITY_CLIENT_SECRET` 并临时写进这里，构建完由 trap 还原。
 *
 * 为什么不直接改 `Antigravity.java`：那个文件会同步到公库。
 * 密钥只应存在于私库的构建环境里，不应该跟着源码走。
 *
 * 注意：Antigravity 用的是 Google 原生应用的 OAuth 客户端凭据，
 * 按 Google 的定义它不属于机密（native app 的 secret 不提供保密性，
 * 安全性靠 PKCE/回环重定向）。这里仍然按私密内容管理，避免出现在公库。
 */
final class BuildSecrets {

    private BuildSecrets() {
    }

    static final String ANTIGRAVITY_CLIENT_SECRET = "";
}
