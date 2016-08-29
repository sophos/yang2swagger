/*
 * Copyright (c) 2016 MRV Communications, Inc. All rights reserved.
 *  This program and the accompanying materials are made available under the
 *  terms of the Eclipse Public License v1.0 which accompanies this distribution,
 *  and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      Christopher Murch <cmurch@mrv.com>
 *      Bartosz Michalik <bartosz.michalik@amartus.com>
 */

package com.mrv.yangtools.codegen.impl;

import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.GroupingDefinition;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author bartosz.michalik@amartus.com
 */
public class GroupingHierarchyHandler {
    private static final Logger log = LoggerFactory.getLogger(GroupingHierarchyHandler.class);
    private final Map<SchemaPath, HierarchyNode> hierarchy;
    private final Map<GroupingDefinition, String> groupingNames;
    private final SchemaContext ctx;

    public GroupingHierarchyHandler(SchemaContext ctx) {
        this.ctx = ctx;
        groupingNames = computeNames();
        hierarchy = buildHierarchy();
    }

    public String getGroupingName(GroupingDefinition d) {
        return groupingNames.get(d);
    }

    private Map<GroupingDefinition, String> computeNames() {
        HashMap<String, Set<QName>> names = new HashMap<>();


        Supplier<Stream<GroupingDefinition>> groupings = () -> DataNodeHelper.stream(ctx).filter(e -> e instanceof GroupingDefinition)
                .map(e -> (GroupingDefinition) e);

        groupings.get().forEach(g -> {
            String name = g.getQName().getLocalName();
            Set<QName> qNames = names.computeIfAbsent(name, (n) -> new HashSet<QName>());
            qNames.add(g.getQName());
        });

        return groupings.get().map(g -> {
            String localName = g.getQName().getLocalName();
            int times = names.get(localName).size();
            if(times < 2) return new Tuple<>(g, localName);
            return new Tuple<>(g, toModuleName(g.getQName()) + ":" + localName);
        }).collect(Collectors.toMap(Tuple::first, Tuple::second));
    }

    private String toModuleName(QName qname) {
        Set<Module> modules = ctx.findModuleByNamespace(qname.getNamespace());
        if(modules.size() != 1) throw new IllegalStateException("no support for " + modules.size() + " modules with name " + qname);
        return modules.iterator().next().getName();
    }

    private Map<SchemaPath, HierarchyNode> buildHierarchy() {
        Map<SchemaPath, HierarchyNode> result = ctx.getGroupings().stream().map(g -> new Tuple<>(g.getPath(), new HierarchyNode(g.getPath())))
                .collect(Collectors.toMap(Tuple::first, Tuple::second));

        ctx.getGroupings().forEach(g -> {
            HierarchyNode node = result.get(g.getPath());
            g.getUses().forEach(u -> {
                HierarchyNode parent = result.get(u.getGroupingPath());
                if (parent == null) {
                    log.warn("Hierarchy creation problem. No grouping with name {} found. Ignoring hierarchy relation.", u.getGroupingPath().getLastComponent());
                } else {
                    node.addParent(parent);
                }

            });
        });
        return result;
    }

    public boolean isParent(SchemaPath parentName, QName forNode) {
        HierarchyNode node = hierarchy.get(forNode);
        if(node == null) {
            log.warn("Node not found for name {}", forNode);
            return false;
        }
        return node.isChildOf(parentName);
    }


    static class HierarchyNode {
        private final Set<HierarchyNode> parents;
        private final SchemaPath name;

        private HierarchyNode(SchemaPath name) {
            this.name = name;
            parents = new HashSet<>();
        }

        public SchemaPath getName() {
            return name;
        }

        public boolean isNamed(SchemaPath name) {
            Objects.requireNonNull(name);
            return name.equals(this.name);
        }

        public boolean isChildOf(SchemaPath name) {
            Objects.requireNonNull(name);
            if(parents.isEmpty()) return false;
            return parents.stream()
                    .map(p -> isNamed(name) || p.isChildOf(name)).findFirst()
                    .orElse(false);
        }

        public void addParent(HierarchyNode parent) {
            parents.add(parent);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HierarchyNode)) return false;
            HierarchyNode that = (HierarchyNode) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

}
