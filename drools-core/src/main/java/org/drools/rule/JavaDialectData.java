package org.drools.rule;

/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayInputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.drools.RuntimeDroolsException;
import org.drools.base.accumulators.JavaAccumulatorFunctionExecutor;
import org.drools.common.DroolsObjectInput;
import org.drools.spi.Accumulator;
import org.drools.spi.Consequence;
import org.drools.spi.EvalExpression;
import org.drools.spi.PredicateExpression;
import org.drools.spi.ReturnValueEvaluator;
import org.drools.spi.ReturnValueExpression;
import org.drools.util.StringUtils;
import org.drools.workflow.core.node.ActionNode;
import org.drools.workflow.instance.impl.ReturnValueConstraintEvaluator;

public class JavaDialectData
    implements
    DialectData,
    Externalizable {

    /**
     *
     */
    private static final long             serialVersionUID = 400L;

    private static final ProtectionDomain PROTECTION_DOMAIN;

    private Map                           invokerLookups;

    private Object                        AST;

    private Map                           store;

    private DialectDatas                  datas;

    private transient PackageClassLoader  classLoader;

    private boolean                       dirty;

    static {
        PROTECTION_DOMAIN = (ProtectionDomain) AccessController.doPrivileged( new PrivilegedAction() {
            public Object run() {
                return JavaDialectData.class.getProtectionDomain();
            }
        } );
    }

    /**
     * Default constructor - for Externalizable. This should never be used by a user, as it
     * will result in an invalid state for the instance.
     */
    public JavaDialectData() {
    }

    public JavaDialectData(final DialectDatas datas) {
        this.datas = datas;
        this.classLoader = new PackageClassLoader( this.datas.getParentClassLoader(), this );
        this.datas.addClassLoader( this.classLoader );
        this.invokerLookups = new HashMap();
        this.store = new HashMap();
        this.dirty = false;
    }

    public DialectData clone() {
        DialectData cloneOne = new JavaDialectData();

        cloneOne.merge(this);
        return cloneOne;
    }

    public void setDialectDatas(DialectDatas datas) {
        this.datas  = datas;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /**
     * Handles the write serialization of the PackageCompilationData. Patterns in Rules may reference generated data which cannot be serialized by
     * default methods. The PackageCompilationData holds a reference to the generated bytecode. The generated bytecode must be restored before any Rules.
     *
     */
    public void writeExternal(ObjectOutput stream) throws IOException {
        stream.writeObject( this.store );
        stream.writeObject( this.AST );
        stream.writeObject( this.invokerLookups );
        stream.writeBoolean( this.dirty );
    }

    /**
     * Handles the read serialization of the PackageCompilationData. Patterns in Rules may reference generated data which cannot be serialized by
     * default methods. The PackageCompilationData holds a reference to the generated bytecode; which must be restored before any Rules.
     * A custom ObjectInputStream, able to resolve classes against the bytecode, is used to restore the Rules.
     *
     */
    public void readExternal(ObjectInput stream) throws IOException,
                                                      ClassNotFoundException {
        DroolsObjectInput droolsStream = (DroolsObjectInput)stream;

        this.datas          = droolsStream.getDialectDatas();
        this.classLoader    = new PackageClassLoader( this.datas.getParentClassLoader(), this );
        this.datas.addClassLoader( this.classLoader );

        this.store = (Map) stream.readObject();
        this.AST = stream.readObject();
        this.invokerLookups = (Map) droolsStream.readObject();
        this.dirty  = droolsStream.readBoolean();
    }

    protected Map getStore() {
        if (store == null) {
            store = new HashMap();
        }
        return store;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public void removeRule(Package pkg,
                           Rule rule) {
        final String consequenceName = rule.getConsequence().getClass().getName();

        // check for compiled code and remove if present.
        if ( remove( consequenceName ) ) {
            removeClasses( rule.getLhs() );

            // Now remove the rule class - the name is a subset of the consequence name
            remove( consequenceName.substring( 0,
                                               consequenceName.indexOf( "ConsequenceInvoker" ) ) );
        }
    }

    public void removeFunction(Package pkg,
                               Function function) {
        remove( pkg.getName() + "." + StringUtils.ucFirst( function.getName() ) );
    }

    public void merge(DialectData newData) {
        JavaDialectData newJavaData = (JavaDialectData) newData;

        this.dirty = newData.isDirty();
        if (this.classLoader == null) {
            this.classLoader    = new PackageClassLoader(newJavaData.getClassLoader().getParent(), this);
            this.dirty = true;
        }

        // First update the binary files
        // @todo: this probably has issues if you add classes in the incorrect order - functions, rules, invokers.
        for ( String file : newJavaData.list()) {
            // no need to wire, as we already know this is done in a merge
            if (getStore().put( file,
                                newJavaData.read( file ) ) != null ) {
                // we are updating an existing class so reload();
                this.dirty = true;
            }
        }

        // Add invokers
        putAllInvokers( newJavaData.getInvokers() );
    }

    private void removeClasses(final ConditionalElement ce) {
        if ( ce instanceof GroupElement ) {
            final GroupElement group = (GroupElement) ce;
            for ( final Iterator it = group.getChildren().iterator(); it.hasNext(); ) {
                final Object object = it.next();
                if ( object instanceof ConditionalElement ) {
                    removeClasses( (ConditionalElement) object );
                } else if ( object instanceof Pattern ) {
                    removeClasses( (Pattern) object );
                }
            }
        } else if ( ce instanceof EvalCondition ) {
            remove( ((EvalCondition) ce).getEvalExpression().getClass().getName() );
        }
    }

    private void removeClasses(final Pattern pattern) {
        for ( final Iterator it = pattern.getConstraints().iterator(); it.hasNext(); ) {
            final Object object = it.next();
            if ( object instanceof PredicateConstraint ) {
                remove( ((PredicateConstraint) object).getPredicateExpression().getClass().getName() );
            } else if ( object instanceof ReturnValueConstraint ) {
                remove( ((ReturnValueConstraint) object).getExpression().getClass().getName() );
            }
        }
    }

    public byte[] read(final String resourceName) {
        byte[] bytes = null;

        if ( !getStore().isEmpty()) {
            bytes = (byte[])getStore().get( resourceName );
        }
        return bytes;
    }

    public void write(final String resourceName,
                      final byte[] clazzData) throws RuntimeDroolsException {
        if ( getStore().put( resourceName,
                             clazzData ) != null ) {
            // we are updating an existing class so reload();
            //reload();
            this.dirty = true;
        } else {
            try {
                wire( convertResourceToClassName( resourceName ) );
            } catch ( final Exception e ) {
                e.printStackTrace();
                throw new RuntimeDroolsException( e );
            }
        }

    }

    public boolean remove(final String resourceName) throws RuntimeDroolsException {
        getInvokers().remove( resourceName );
        if (getStore().remove( convertClassToResourcePath( resourceName ) ) != null ) {
            // we need to make sure the class is removed from the classLoader
            // reload();
            this.dirty = true;
            return true;
        }
        return false;
    }

    public String[] list() {
        String[] names = new String[getStore().size()];
        int i = 0;

        for ( Object object : getStore().keySet()) {
            names[i++] = (String)object;
        }
        return names;
    }

    /**
     * This class drops the classLoader and reloads it. During this process  it must re-wire all the invokeables.
     * @throws RuntimeDroolsException
     */
    public void reload() throws RuntimeDroolsException {
        // drops the classLoader and adds a new one
        this.datas.removeClassLoader( this.classLoader );
        this.classLoader = new PackageClassLoader( this.datas.getParentClassLoader(), this );
        this.datas.addClassLoader( this.classLoader );

        // Wire up invokers
        try {
            for ( final Object object : getInvokers().entrySet() ) {
                Entry entry = (Entry) object;
                wire( (String) entry.getKey(),
                      entry.getValue() );
            }
        } catch ( final ClassNotFoundException e ) {
            throw new RuntimeDroolsException( e );
        } catch ( final InstantiationError e ) {
            throw new RuntimeDroolsException( e );
        } catch ( final IllegalAccessException e ) {
            throw new RuntimeDroolsException( e );
        } catch ( final InstantiationException e ) {
            throw new RuntimeDroolsException( e );
        } finally {
            this.dirty = false;
        }
    }

    public void clear() {
        getStore().clear();
        getInvokers().clear();
        this.AST = null;
        reload();
    }

    public void wire(final String className) throws ClassNotFoundException,
                                            InstantiationException,
                                            IllegalAccessException {
        final Object invoker = getInvokers().get( className );
        wire( className,
              invoker );
    }

    public void wire(final String className,
                     final Object invoker) throws ClassNotFoundException,
                                          InstantiationException,
                                          IllegalAccessException {
        final Class clazz = this.classLoader.findClass( className );

        if (clazz != null) {
            if ( invoker instanceof ReturnValueRestriction ) {
                ((ReturnValueRestriction) invoker).setReturnValueExpression( (ReturnValueExpression) clazz.newInstance() );
            } else if ( invoker instanceof PredicateConstraint ) {
                ((PredicateConstraint) invoker).setPredicateExpression( (PredicateExpression) clazz.newInstance() );
            } else if ( invoker instanceof EvalCondition ) {
                ((EvalCondition) invoker).setEvalExpression( (EvalExpression) clazz.newInstance() );
            } else if ( invoker instanceof Accumulate ) {
                ((Accumulate) invoker).setAccumulator( (Accumulator) clazz.newInstance() );
            } else if ( invoker instanceof Rule ) {
                ((Rule) invoker).setConsequence( (Consequence) clazz.newInstance() );
            } else if ( invoker instanceof JavaAccumulatorFunctionExecutor ) {
                ((JavaAccumulatorFunctionExecutor) invoker).setExpression( (ReturnValueExpression) clazz.newInstance() );
            } else if ( invoker instanceof ActionNode ) {
                ((ActionNode) invoker).setAction( clazz.newInstance() );
            } else if ( invoker instanceof ReturnValueConstraintEvaluator ) {
                ((ReturnValueConstraintEvaluator) invoker).setEvaluator( (ReturnValueEvaluator) clazz.newInstance() );
            }
        }
        else {
            throw new ClassNotFoundException(className);
        }
    }

    public String toString() {
        return this.getClass().getName() +getStore().toString();
    }

    public void putInvoker(final String className,
                           final Object invoker) {
        getInvokers().put( className,
                                 invoker );
    }

    public void putAllInvokers(final Map invokers) {
        getInvokers().putAll( invokers );

    }

    public Map getInvokers() {
        if (this.invokerLookups == null) {
            this.invokerLookups = new HashMap();
        }
        return this.invokerLookups;
    }

    public void removeInvoker(final String className) {
        getInvokers().remove( className );
    }

    public Object getAST() {
        return this.AST;
    }

    public void setAST(final Object ast) {
        this.AST = ast;
    }

    /**
     * Lifted and adapted from Jakarta commons-jci
     *
     * @author mproctor
     *
     */
    public static class PackageClassLoader extends ClassLoader
        implements
        DroolsClassLoader {
        private JavaDialectData parent;

        public PackageClassLoader() {
        }

        public PackageClassLoader(final ClassLoader parentClassLoader, JavaDialectData parent) {
            super( parentClassLoader );
            this.parent = parent;
        }

        public Class fastFindClass(final String name) {
            final Class clazz = findLoadedClass( name );

            if ( clazz == null && parent != null) {
                final byte[] clazzBytes = parent.read( convertClassToResourcePath( name ) );
                if ( clazzBytes != null ) {
                    return defineClass( name,
                                        clazzBytes,
                                        0,
                                        clazzBytes.length,
                                        PROTECTION_DOMAIN );
                }
            }

            return clazz;
        }

        /**
         * Javadocs recommend that this method not be overloaded. We overload this so that we can prioritise the fastFindClass
         * over method calls to parent.loadClass(name, false); and c = findBootstrapClass0(name); which the default implementation
         * would first - hence why we call it "fastFindClass" instead of standard findClass, this indicates that we give it a
         * higher priority than normal.
         *
         */
        protected synchronized Class loadClass(final String name,
                                               final boolean resolve) throws ClassNotFoundException {
            Class clazz = fastFindClass( name );

            if ( clazz == null ) {
                final ClassLoader parent = getParent();
                if ( parent != null ) {
                    clazz = Class.forName( name,
                                           true,
                                           parent );
                }
            }

            if ( resolve && clazz != null) {
                resolveClass( clazz );
            }

            return clazz;
        }

        protected Class findClass(final String name) throws ClassNotFoundException {
            return fastFindClass( name );
        }

        public InputStream getResourceAsStream(final String name) {
            final byte[] bytes = (byte[]) parent.store.get( name );
            if ( bytes != null ) {
                return new ByteArrayInputStream( bytes );
            } else {
                InputStream input = this.getParent().getResourceAsStream( name );
                if ( input == null ) {
                    input = super.getResourceAsStream( name );
                }
                return input;
            }
        }
    }

    /**
     * Please do not use - internal
     * org/my/Class.xxx -> org.my.Class
     */
    public static String convertResourceToClassName(final String pResourceName) {
        return stripExtension( pResourceName ).replace( '/',
                                                        '.' );
    }

    /**
     * Please do not use - internal
     * org.my.Class -> org/my/Class.class
     */
    public static String convertClassToResourcePath(final String pName) {
        return pName.replace( '.',
                              '/' ) + ".class";
    }

    /**
     * Please do not use - internal
     * org/my/Class.xxx -> org/my/Class
     */
    public static String stripExtension(final String pResourceName) {
        final int i = pResourceName.lastIndexOf( '.' );
        final String withoutExtension = pResourceName.substring( 0,
                                                                 i );
        return withoutExtension;
    }

}