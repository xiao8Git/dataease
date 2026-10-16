package io.dataease.filter;

/** Supplies the managed frame-ancestors policy; null preserves deployment configuration. */
public interface FramePolicyProvider {
    String frameAncestors();
}
