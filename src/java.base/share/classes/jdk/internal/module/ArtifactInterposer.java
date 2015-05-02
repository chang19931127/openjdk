/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.internal.module;

import java.io.IOException;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * Interposes on a {@code ModuleArtifactFinder} to augment module descriptors
 * with additional requires or exports.
 *
 * This class is intended for use with the VM options AddModuleRequires and
 * AddModuleExports to augment module descriptors with additional requires
 * and exports. Example usages are:
 *
 * <pre>{@code
 *     -XX:AddModuleRequires:jtreg=java.base
 *
 *     -XX:AddModuleExports=java.base/sun.misc=jlib,java.base/sun.reflect
 * }</pre>
 */

class ArtifactInterposer implements ModuleArtifactFinder {

    // the underlying finder
    private final ModuleArtifactFinder finder;

    // module name -> module dependence
    private final Map<String, Set<String>> requiresAdditions;

    // module name -> package name -> set of export additions
    private final Map<String, Map<String, Set<String>>> exportAdditions;

    // Unique set to represent unqualified exports
    private static final Set<String> ALL
        = Collections.unmodifiableSet(new HashSet<>());

    // module name -> artifact
    private final Map<String, ModuleArtifact> artifacts = new HashMap<>();

    // true if all modules have been cached
    private boolean haveAllModules;

    private ArtifactInterposer(ModuleArtifactFinder finder,
                               Map<String, Set<String>> requiresAdditions,
                               Map<String, Map<String, Set<String>>> exportAdditions)
    {
        this.finder = finder;
        this.requiresAdditions = requiresAdditions;
        this.exportAdditions = exportAdditions;
    }

    /**
     * Returns a finder that interposes on the given finder.
     *
     * @throws IllegalArgumentException if expressions specified to
     *     {@code addModuleRequiresValue} or {@code addModuleExportsValue}
     *     cannot be parsed
     */
    static ModuleArtifactFinder interpose(ModuleArtifactFinder finder,
                                          String addModuleRequiresValue,
                                          String addModuleExportsValue)
    {
        Map<String, Set<String>> requiresAdditions = new HashMap<>();
        if (addModuleRequiresValue != null) {
            // parse value of AddModuleRequires
            for (String expr: addModuleRequiresValue.split(",")) {
                String[] s = expr.split("=");
                if (s.length != 2)
                    throw parseFailure(expr);
                String m1 = s[0];
                String m2 = s[1];
                requiresAdditions.computeIfAbsent(m1, k -> new HashSet<>()).add(m2);
            }
        }

        Map<String, Map<String, Set<String>>> exportAdditions = new HashMap<>();
        if (addModuleExportsValue != null) {
            // parse value of AddModuleExports
            for (String expr: addModuleExportsValue.split(",")) {
                String[] s = expr.split("/");
                if (s.length != 2)
                    throw parseFailure(expr);
                String module = s[0];
                s = s[1].split("=");
                String pkg = s[0];
                Map<String, Set<String>> pkgToAdds
                    = exportAdditions.computeIfAbsent(module,
                                                      k -> new HashMap<>());
                if (s.length == 1) {
                    pkgToAdds.put(pkg, ALL);
                } else if (s.length == 2) {
                    pkgToAdds.computeIfAbsent(pkg, k -> new HashSet<>())
                        .add(s[1]);
                } else {
                    throw parseFailure(expr);
                }
            }
        }

        return new ArtifactInterposer(finder, requiresAdditions, exportAdditions);
    }

    @Override
    public ModuleArtifact find(String name) {
        ModuleArtifact artifact = artifacts.get(name);
        if (artifact != null)
            return artifact;

        artifact = finder.find(name);
        if (artifact == null)
            return null;

        artifact = replaceIfNeeded(artifact);
        artifacts.put(name, artifact);
        return artifact;
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        if (!haveAllModules) {
            for (ModuleArtifact artifact: finder.allModules()) {
                String name = artifact.descriptor().name();
                artifacts.computeIfAbsent(name, k -> replaceIfNeeded(artifact));
            }
            haveAllModules = true;
        }
        return new HashSet<>(artifacts.values());
    }

    /**
     * Returns the given module artifact or a replacement with an updated
     * module descriptor with additional requires or widened exports.
     */
    private ModuleArtifact replaceIfNeeded(ModuleArtifact artifact) {
        ModuleDescriptor descriptor = artifact.descriptor();
        String name = descriptor.name();

        Set<String> requiresAdds = requiresAdditions.get(name);
        Map<String, Set<String>> exportAdds = exportAdditions.get(name);

        if (requiresAdds == null && exportAdds == null)
            return artifact;  // no changes requested

        ModuleDescriptor.Builder mdb = new ModuleDescriptor.Builder(name);

        // Requires
        descriptor.requires().forEach(rq -> mdb.requires(rq.modifiers(),
                                                         rq.name()));
        if (requiresAdds != null)
            requiresAdds.forEach(mn -> mdb.requires(mn));

        // Exports
        //
        // If AddModuleExports specifies an unqualified export then any
        // qualified exports of that package are dropped.  If a package already
        // has an unqualified export and AddModuleExports specifies a qualified
        // export then the qualified export is ignored.

        if (exportAdds == null) {
            // Nothing to add, so just copy all existing exports
            for (Exports e : descriptor.exports()) {
                e.targets().ifPresentOrElse(ts -> mdb.exports(e.source(), ts),
                                            () -> mdb.exports(e.source()));
            }
        } else {

            // Process existing exports
            for (Exports e : descriptor.exports()) {
                String pkg = e.source();
                Set<String> adds = exportAdds.get(pkg);
                if (adds != null) {
                    if (!e.targets().isPresent()) {
                        // Already exported to all, so any requested changes to
                        // the exporting of the package should be ignored
                        mdb.exports(e.source());
                    } else {
                        // Already has a qualified export
                        if (adds == ALL) {
                            // Convert this to an unqualified export
                            mdb.exports(pkg);
                        } else {
                            // Append to this qualified export
                            HashSet<String> ts
                                = new HashSet<>(e.targets().get());
                            ts.addAll(adds);
                            mdb.exports(pkg, ts);
                        }
                    }
                    exportAdds.remove(pkg);
                } else {
                    e.targets().ifPresentOrElse(ts -> mdb.exports(e.source(), ts),
                                                () -> mdb.exports(e.source()));
                }
            }

            // Process remaining additions
            for (Map.Entry<String, Set<String>> add : exportAdds.entrySet()) {
                String pkg = add.getKey();
                Set<String> adds = add.getValue();
                if (adds == ALL)
                    mdb.exports(pkg);
                else
                    mdb.exports(pkg, new HashSet<>(adds));
            }

        }

        // Copy over uses, provides, and version
        descriptor.uses().forEach(mdb::uses);
        descriptor.provides().values()
            .forEach(p -> mdb.provides(p.service(), p.providers()));
        descriptor.version().ifPresent(v -> mdb.version(v.toString())); // ##

        // Return a new ModuleArtifact with the new module descriptor
        Set<String> packages = artifact.packages();
        URI location = artifact.location();
        return new ModuleArtifact(mdb.build(), packages, location) {
            @Override
            public ModuleReader open() throws IOException {
                return artifact.open();
            }
        };
    }

    private static IllegalArgumentException parseFailure(String expr) {
        return new IllegalArgumentException("'" + expr + "' cannot be parsed");
    }

}
