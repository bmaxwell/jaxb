package com.sun.xml.bind.v2.runtime.property;

import java.io.IOException;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.DomHandler;
import javax.xml.stream.XMLStreamException;

import com.sun.xml.bind.api.AccessorException;
import com.sun.xml.bind.v2.ClassFactory;
import com.sun.xml.bind.v2.model.core.PropertyKind;
import com.sun.xml.bind.v2.model.core.WildcardMode;
import com.sun.xml.bind.v2.model.runtime.RuntimeElement;
import com.sun.xml.bind.v2.model.runtime.RuntimeReferencePropertyInfo;
import com.sun.xml.bind.v2.runtime.ElementBeanInfoImpl;
import com.sun.xml.bind.v2.runtime.JAXBContextImpl;
import com.sun.xml.bind.v2.runtime.JaxBeanInfo;
import com.sun.xml.bind.v2.runtime.XMLSerializer;
import com.sun.xml.bind.v2.runtime.reflect.Accessor;
import com.sun.xml.bind.v2.runtime.unmarshaller.ChildLoader;
import com.sun.xml.bind.v2.runtime.unmarshaller.WildcardLoader;
import com.sun.xml.bind.v2.util.QNameMap;

import org.xml.sax.SAXException;

/**
 * @author Kohsuke Kawaguchi
 */
final class SingleReferenceNodeProperty<BeanT,ValueT> extends PropertyImpl<BeanT> {

    private final Accessor<BeanT,ValueT> acc;

    private final QNameMap<JaxBeanInfo> expectedElements = new QNameMap<JaxBeanInfo>();

    private final DomHandler domHandler;
    private final WildcardMode wcMode;

    public SingleReferenceNodeProperty(JAXBContextImpl p, RuntimeReferencePropertyInfo prop) {
        super(p,prop);
        acc = prop.getAccessor().optimize();

        for (RuntimeElement e : prop.getElements()) {
            expectedElements.put( e.getElementName(), p.getOrCreate(e) );
        }

        if(prop.getWildcard()!=null) {
            domHandler = (DomHandler) ClassFactory.create(prop.getDOMHandler());
            wcMode = prop.getWildcard();
        } else {
            domHandler = null;
            wcMode = null;
        }
    }

    public void reset(BeanT bean) throws AccessorException {
        acc.set(bean,null);
    }

    public String getIdValue(BeanT beanT) {
        return null;
    }

    public void serializeBody(BeanT o, XMLSerializer w, Object outerPeer) throws SAXException, AccessorException, IOException, XMLStreamException {
        ValueT v = acc.get(o);
        if(v!=null) {
            try {
                JaxBeanInfo bi = w.grammar.getBeanInfo(v,true);
                if(bi.jaxbType==Object.class && domHandler!=null)
                    // even if 'v' is a DOM node, it always derive from Object,
                    // so the getBeanInfo returns BeanInfo for Object
                    w.writeDom(v,domHandler,o,fieldName);
                else
                    bi.serializeRoot(v,w);
            } catch (JAXBException e) {
                w.reportError(fieldName,e);
                // recover by ignoring this property
            }
        }
    }

    public void buildChildElementUnmarshallers(UnmarshallerChain chain, QNameMap<ChildLoader> handlers) {
        for (QNameMap.Entry<JaxBeanInfo> n : expectedElements.entrySet())
            handlers.put(n.nsUri,n.localName, new ChildLoader(n.getValue().getLoader(chain.context,true),acc));

        if(domHandler!=null)
            handlers.put(CATCH_ALL,new ChildLoader(new WildcardLoader(domHandler,wcMode),acc));

    }

    public PropertyKind getKind() {
        return PropertyKind.REFERENCE;
    }

    @Override
    public Accessor getElementPropertyAccessor(String nsUri, String localName) {
        JaxBeanInfo bi = expectedElements.get(nsUri, localName);
        if(bi!=null) {
            if(bi instanceof ElementBeanInfoImpl) {
                final ElementBeanInfoImpl ebi = (ElementBeanInfoImpl) bi;
                // a JAXBElement. We need to handle JAXBElement for JAX-WS
                return new Accessor<BeanT,Object>(ebi.expectedType) {
                    public Object get(BeanT bean) throws AccessorException {
                        ValueT r = acc.get(bean);
                        if(r instanceof JAXBElement) {
                            return ((JAXBElement)r).getValue();
                        } else
                            // this is sloppy programming, but hey...
                            return r;
                    }

                    public void set(BeanT bean, Object value) throws AccessorException {
                        if(value!=null) {
                            value = ebi.createInstanceFromValue(value);
                        }
                        acc.set(bean,(ValueT)value);
                    }
                };
            } else {
                // a custom element type, like @XmlRootElement class Foo { ... }
                return acc;
            }
        } else
            return null;
    }
}