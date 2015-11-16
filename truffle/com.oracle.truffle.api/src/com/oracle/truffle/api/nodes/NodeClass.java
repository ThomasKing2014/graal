/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.nodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.nodes.NodeFieldAccessor.NodeFieldAccessorImpl;
import com.oracle.truffle.api.nodes.NodeFieldAccessor.NodeFieldKind;

public interface NodeClass {

    public static class Lookup {
        public static NodeClass get(Class<? extends Node> clazz) {
            return NodeClassImpl.nodeClasses.get(clazz);
        }

        public static NodeClass get(Node node) {
            return node.getNodeClass();
        }
    }

    NodeFieldAccessor getNodeClassField();

    NodeFieldAccessor[] getCloneableFields();

    NodeFieldAccessor[] getFields();

    NodeFieldAccessor getParentField();

    NodeFieldAccessor[] getChildFields();

    NodeFieldAccessor[] getChildrenFields();

    Iterator<Node> makeIterator(Node node);

    /**
     * Information about a {@link Node} class. A single instance of this class is allocated for
     * every subclass of {@link Node} that is used.
     */
    public final class NodeClassImpl implements NodeClass {
        static final ClassValue<NodeClass> nodeClasses = new ClassValue<NodeClass>() {
            @SuppressWarnings("unchecked")
            @Override
            protected NodeClass computeValue(final Class<?> clazz) {
                assert Node.class.isAssignableFrom(clazz);
                return AccessController.doPrivileged(new PrivilegedAction<NodeClass>() {
                    public NodeClass run() {
                        return new NodeClassImpl((Class<? extends Node>) clazz);
                    }
                });
            }
        };

        private static final NodeFieldAccessor[] EMPTY_NODE_FIELD_ARRAY = new NodeFieldAccessor[0];

        // The comprehensive list of all fields.
        private final NodeFieldAccessor[] fields;
        // Separate arrays for the frequently accessed fields.
        private final NodeFieldAccessor parentField;
        private final NodeFieldAccessor nodeClassField;
        private final NodeFieldAccessor[] childFields;
        private final NodeFieldAccessor[] childrenFields;
        private final NodeFieldAccessor[] cloneableFields;

        private final Class<? extends Node> clazz;

        public static NodeClass get(Class<? extends Node> clazz) {
            return nodeClasses.get(clazz);
        }

        public static NodeClass get(Node node) {
            return node.getNodeClass();
        }

        NodeClassImpl(Class<? extends Node> clazz) {
            List<NodeFieldAccessor> fieldsList = new ArrayList<>();
            NodeFieldAccessor parentFieldTmp = null;
            NodeFieldAccessor nodeClassFieldTmp = null;
            List<NodeFieldAccessor> childFieldList = new ArrayList<>();
            List<NodeFieldAccessor> childrenFieldList = new ArrayList<>();
            List<NodeFieldAccessor> cloneableFieldList = new ArrayList<>();

            for (Field field : NodeUtil.getAllFields(clazz)) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                NodeFieldAccessor nodeField;
                if (field.getDeclaringClass() == Node.class && field.getName().equals("parent")) {
                    assert Node.class.isAssignableFrom(field.getType());
                    nodeField = NodeFieldAccessorImpl.create(NodeFieldKind.PARENT, field);
                    parentFieldTmp = nodeField;
                } else if (field.getDeclaringClass() == Node.class && field.getName().equals("nodeClass")) {
                    assert NodeClass.class.isAssignableFrom(field.getType());
                    nodeField = NodeFieldAccessorImpl.create(NodeFieldKind.NODE_CLASS, field);
                    nodeClassFieldTmp = nodeField;
                } else if (field.getAnnotation(Child.class) != null) {
                    checkChildField(field);
                    nodeField = NodeFieldAccessorImpl.create(NodeFieldKind.CHILD, field);
                    childFieldList.add(nodeField);
                } else if (field.getAnnotation(Children.class) != null) {
                    checkChildrenField(field);
                    nodeField = NodeFieldAccessorImpl.create(NodeFieldKind.CHILDREN, field);
                    childrenFieldList.add(nodeField);
                } else {
                    nodeField = NodeFieldAccessorImpl.create(NodeFieldKind.DATA, field);
                    if (NodeCloneable.class.isAssignableFrom(field.getType())) {
                        cloneableFieldList.add(nodeField);
                    }
                }
                fieldsList.add(nodeField);
            }

            if (parentFieldTmp == null) {
                throw new AssertionError("parent field not found");
            }

            this.fields = fieldsList.toArray(EMPTY_NODE_FIELD_ARRAY);
            this.nodeClassField = nodeClassFieldTmp;
            this.parentField = parentFieldTmp;
            this.childFields = childFieldList.toArray(EMPTY_NODE_FIELD_ARRAY);
            this.childrenFields = childrenFieldList.toArray(EMPTY_NODE_FIELD_ARRAY);
            this.cloneableFields = cloneableFieldList.toArray(EMPTY_NODE_FIELD_ARRAY);
            this.clazz = clazz;
        }

        public Class<? extends Node> getNodeClass() {
            return clazz;
        }

        public NodeFieldAccessor getNodeClassField() {
            return nodeClassField;
        }

        public NodeFieldAccessor[] getCloneableFields() {
            return cloneableFields;
        }

        private static boolean isNodeType(Class<?> clazz) {
            return Node.class.isAssignableFrom(clazz) || (clazz.isInterface() && NodeInterface.class.isAssignableFrom(clazz));
        }

        private static void checkChildField(Field field) {
            if (!isNodeType(field.getType())) {
                throw new AssertionError("@Child field type must be a subclass of Node or an interface extending NodeInterface (" + field + ")");
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new AssertionError("@Child field must not be final (" + field + ")");
            }
        }

        private static void checkChildrenField(Field field) {
            if (!(field.getType().isArray() && isNodeType(field.getType().getComponentType()))) {
                throw new AssertionError("@Children field type must be an array of a subclass of Node or an interface extending NodeInterface (" + field + ")");
            }
            if (!Modifier.isFinal(field.getModifiers())) {
                throw new AssertionError("@Children field must be final (" + field + ")");
            }
        }

        public NodeFieldAccessor[] getFields() {
            return fields;
        }

        public NodeFieldAccessor getParentField() {
            return parentField;
        }

        public NodeFieldAccessor[] getChildFields() {
            return childFields;
        }

        public NodeFieldAccessor[] getChildrenFields() {
            return childrenFields;
        }

        @Override
        public int hashCode() {
            return clazz.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof NodeClassImpl) {
                NodeClassImpl other = (NodeClassImpl) obj;
                return clazz.equals(other.clazz);
            }
            return false;
        }

        public Iterator<Node> makeIterator(Node node) {
            assert clazz.isInstance(node);
            return new NodeIterator(this, node);
        }
    }

    public static final class NodeIterator implements Iterator<Node> {
        private final NodeFieldAccessor[] childFields;
        private final NodeFieldAccessor[] childrenFields;
        private final Node node;
        private final int childrenCount;
        private int index;

        public NodeIterator(NodeClass nodeClass, Node node) {
            this.childFields = nodeClass.getChildFields();
            this.childrenFields = nodeClass.getChildrenFields();
            this.node = node;
            this.childrenCount = childrenCount();
            this.index = 0;
        }

        private int childrenCount() {
            int nodeCount = childFields.length;
            for (NodeFieldAccessor childrenField : childrenFields) {
                Object[] children = ((Object[]) childrenField.getObject(node));
                if (children != null) {
                    nodeCount += children.length;
                }
            }
            return nodeCount;
        }

        private Node nodeAt(int idx) {
            int nodeCount = childFields.length;
            if (idx < nodeCount) {
                return (Node) childFields[idx].getObject(node);
            } else {
                for (NodeFieldAccessor childrenField : childrenFields) {
                    Object[] nodeArray = (Object[]) childrenField.getObject(node);
                    if (idx < nodeCount + nodeArray.length) {
                        return (Node) nodeArray[idx - nodeCount];
                    }
                    nodeCount += nodeArray.length;
                }
            }
            return null;
        }

        private void forward() {
            if (index < childrenCount) {
                index++;
            }
        }

        public boolean hasNext() {
            return index < childrenCount;
        }

        public Node next() {
            try {
                return nodeAt(index);
            } finally {
                forward();
            }
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}