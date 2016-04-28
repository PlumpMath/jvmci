/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.vm.ci.hotspot.services;

import jdk.vm.ci.code.CompiledCode;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.JVMCIMetaAccessContext;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Service-provider class for responding to VM events and for creating
 * {@link JVMCIMetaAccessContext}s.
 */
public abstract class HotSpotVMEventListener {

    private static Void checkPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("jvmci"));
        }
        return null;
    }

    @SuppressWarnings("unused")
    HotSpotVMEventListener(Void ignore) {
    }

    /**
     * Initializes a new instance of this class.
     *
     * @throws SecurityException if a security manager has been installed and it denies
     *             {@code RuntimePermission("jvmci")}
     */
    protected HotSpotVMEventListener() {
        this(checkPermission());
    }

    /**
     * Notifies this client that the VM is shutting down.
     */
    public void notifyShutdown() {
    }

    /**
     * Notify on successful install into the code cache.
     *
     * @param hotSpotCodeCacheProvider
     * @param installedCode
     * @param compiledCode
     */
    public void notifyInstall(HotSpotCodeCacheProvider hotSpotCodeCacheProvider, InstalledCode installedCode, CompiledCode compiledCode) {
    }

    /**
     * Create a custom {@link JVMCIMetaAccessContext} to be used for managing the lifetime of loaded
     * metadata. It a custom one isn't created then the default implementation will be a single
     * context with globally shared instances of {@link ResolvedJavaType} that are never released.
     *
     * @param runtime the runtime instance that will use the returned context
     * @return a custom context or null
     */
    public JVMCIMetaAccessContext createMetaAccessContext(HotSpotJVMCIRuntime runtime) {
        return null;
    }
}
