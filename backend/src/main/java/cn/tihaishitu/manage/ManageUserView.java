package cn.tihaishitu.manage;

import java.util.Set;

public record ManageUserView(
        String id,
        String username,
        String displayName,
        String status,
        Set<String> roles,
        long revision) {}
