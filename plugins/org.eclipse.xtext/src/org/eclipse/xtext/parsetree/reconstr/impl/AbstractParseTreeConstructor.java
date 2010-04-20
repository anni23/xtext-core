/*******************************************************************************
 * Copyright (c) 2009 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.parsetree.reconstr.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.AbstractElement;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.Assignment;
import org.eclipse.xtext.CrossReference;
import org.eclipse.xtext.GrammarUtil;
import org.eclipse.xtext.IGrammarAccess;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.parsetree.AbstractNode;
import org.eclipse.xtext.parsetree.CompositeNode;
import org.eclipse.xtext.parsetree.LeafNode;
import org.eclipse.xtext.parsetree.NodeUtil;
import org.eclipse.xtext.parsetree.reconstr.ICommentAssociater;
import org.eclipse.xtext.parsetree.reconstr.IHiddenTokenHelper;
import org.eclipse.xtext.parsetree.reconstr.IInstanceDescription;
import org.eclipse.xtext.parsetree.reconstr.IParseTreeConstructor;
import org.eclipse.xtext.parsetree.reconstr.ITokenSerializer;
import org.eclipse.xtext.parsetree.reconstr.ITokenSerializer.ICrossReferenceSerializer;
import org.eclipse.xtext.parsetree.reconstr.ITokenSerializer.IEnumLiteralSerializer;
import org.eclipse.xtext.parsetree.reconstr.ITokenSerializer.IKeywordSerializer;
import org.eclipse.xtext.parsetree.reconstr.ITokenSerializer.IValueSerializer;
import org.eclipse.xtext.parsetree.reconstr.ITokenStream;
import org.eclipse.xtext.parsetree.reconstr.ITransientValueService;
import org.eclipse.xtext.parsetree.reconstr.XtextSerializationException;
import org.eclipse.xtext.util.EmfFormatter;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;

/**
 * @author Moritz Eysholdt - Initial contribution and API
 */
public abstract class AbstractParseTreeConstructor implements IParseTreeConstructor {

	public abstract class AbstractToken {
		protected List<AbstractToken> children = Collections.emptyList();
		protected final IInstanceDescription current;
		protected final AbstractToken next;
		protected final int no;
		protected AbstractNode node;
		protected final AbstractToken parent;

		public AbstractToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			this.next = next;
			this.parent = parent;
			this.no = no;
			this.current = current;
		}

		protected boolean checkForRecursion(Class<?> clazz, IInstanceDescription curr) {
			AbstractToken token = next;
			while (token != null) {
				if (token.getClass() == clazz)
					return token.getCurrent() == curr;
				token = token.getNext();
			}
			return false;
		}

		public AbstractToken createFollower(int index, IInstanceDescription inst) {
			return null;
		}

		public AbstractToken createParentFollower(AbstractToken next, int index, IInstanceDescription inst) {
			return createParentFollower(next, index, index, inst);
		}

		public AbstractToken createParentFollower(AbstractToken next, int actIndex, int index, IInstanceDescription inst) {
			return null;
		}

		public boolean equalsOrReplacesNode(AbstractNode node) {
			return false;
		}

		public List<AbstractToken> getChildren() {
			return children;
		}

		public IInstanceDescription getCurrent() {
			return current;
		}

		public String getDiagnostic() {
			return null;
		}

		public abstract AbstractElement getGrammarElement();

		public AbstractToken getNext() {
			return next;
		}

		public int getNo() {
			return no;
		}

		public AbstractNode getNode() {
			return node;
		}

		public AbstractToken getParent() {
			return parent;
		}

		public String serialize(int depth, int length, boolean appendDots) {
			ArrayList<String> tokens = new ArrayList<String>();
			AbstractToken t = this;
			while (t != null && tokens.size() <= depth + 1) {
				String s = t.serializeThis(null);
				if (s != null)
					tokens.add(s);
				t = t.getNext();
			}
			boolean overdepth = tokens.size() > depth;
			if (overdepth)
				tokens.remove(tokens.size() - 1);
			StringBuffer r = new StringBuffer();
			for (int i = 0; i < tokens.size(); i++) {
				r.append(tokens.get(i));
				if (i != tokens.size() - 1)
					r.append(" ");
			}
			boolean overlength = r.length() > length;
			if (overlength)
				r.delete(length + 1, r.length());
			if (appendDots && (overdepth || overlength))
				r.append("...");
			return r.toString();
		}

		public final String serializeThis(AbstractNode node) {
			String r = serializeThisInternal(node);
			if (r != ITokenSerializer.KEEP_VALUE_FROM_NODE_MODEL)
				return r;
			if (node == null)
				throw new UnsupportedOperationException(
						"Can not keep value from Node Model when there is no Node Model. Context:" + this);
			else
				return tokenUtil.serializeNode(node);
		}

		protected String serializeThisInternal(AbstractNode node) {
			return null;
		}

		public void setNode(AbstractNode node) {
			this.node = node;
		}

		public IInstanceDescription tryConsume() {
			return tryConsumeVal();
		}

		protected IInstanceDescription tryConsumeVal() {
			return current;
		}
	}

	public abstract class ActionToken extends AbstractToken {

		public ActionToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}
	}

	public abstract class AlternativesToken extends AbstractToken {
		public AlternativesToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}
	}

	public abstract class AssignmentToken extends AbstractToken {

		protected IInstanceDescription consumed;

		protected AbstractElement element;

		protected AssignmentType type;

		protected Object value;

		public AssignmentToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}

		@Override
		public boolean equalsOrReplacesNode(AbstractNode node) {
			if (type == null)
				return false;
			switch (type) {
				case CR:
					return crossRefSerializer.equalsOrReplacesNode(current.getDelegate(), (CrossReference) element,
							(EObject) value, node);
				case KW:
					return keywordSerializer.equalsOrReplacesNode(current.getDelegate(), ((Keyword) element), value,
							node);
				case LRC:
					return valueSerializer.equalsOrReplacesNode(current.getDelegate(), (RuleCall) element, value, node);
				case ERC:
					return enumLitSerializer.equalsOrReplacesNode(current.getDelegate(), (RuleCall) element, value,
							node);
				case PRC:
					return false;
				case DRC:
					return valueSerializer.equalsOrReplacesNode(current.getDelegate(), (RuleCall) element, value, node);
				default:
					return false;
			}
		}

		public AbstractElement getAssignmentElement() {
			return element;
		}

		@Override
		public String getDiagnostic() {
			Assignment ass = (Assignment) getGrammarElement();
			boolean consumable = current.getConsumable(ass.getFeature(), false) != null;
			if (!consumable) {
				EStructuralFeature f = current.getDelegate().eClass().getEStructuralFeature(ass.getFeature());
				if (f == null)
					return "The current object of type '" + current.getDelegate().eClass().getName()
							+ "' does not have a feature named '" + ass.getFeature() + "'";
				String cls = f.getEContainingClass() == current.getDelegate().eClass() ? f.getEContainingClass()
						.getName() : f.getEContainingClass().getName() + "(" + current.getDelegate().eClass().getName()
						+ ")";
				String feat = cls + "." + f.getName();
				if (f.isMany()) {
					int size = ((List<?>) current.getDelegate().eGet(f)).size();
					return "All " + size + " values of " + feat + " have been consumed. "
							+ "More are needed to continue here.";
				} else
					return feat + " is not set.";
			}
			return null;
		}

		public AssignmentType getType() {
			return type;
		}

		public Object getValue() {
			return value;
		}

		@Override
		protected String serializeThisInternal(AbstractNode node) {
			if (type == null)
				return null;
			switch (type) {
				case CR:
					String ref = crossRefSerializer.serializeCrossRef(current.getDelegate(), (CrossReference) element,
							(EObject) value, node);
					if (ref == null) {
						Assignment ass = GrammarUtil.containingAssignment(element);
						throw new XtextSerializationException("Could not serialize cross reference from "
								+ EmfFormatter.objPath(current.getDelegate()) + "." + ass.getFeature() + " to "
								+ EmfFormatter.objPath((EObject) value));
					}
					return ref;
				case KW:
					return keywordSerializer.serializeAssignedKeyword(current.getDelegate(), ((Keyword) element),
							value, node);
				case LRC:
					return valueSerializer.serializeAssignedValue(current.getDelegate(), (RuleCall) element, value,
							node);
				case ERC:
					return enumLitSerializer.serializeAssignedEnumLiteral(current.getDelegate(), (RuleCall) element,
							value, node);
				case PRC:
					return null;
				case DRC:
					return valueSerializer.serializeAssignedValue(current.getDelegate(), (RuleCall) element, value,
							node);
				default:
					return null;
			}
		}
	}

	public enum AssignmentType {
		CR, DRC, ERC, KW, LRC, PRC
	}

	protected class CommentToken extends AbstractToken {

		public CommentToken(LeafNode node) {
			super(null, null, 0, null);
			setNode(node);
		}

		@Override
		public AbstractElement getGrammarElement() {
			return null;
		}

		@Override
		public IInstanceDescription tryConsumeVal() {
			return null;
		}

	}

	public abstract class GroupToken extends AbstractToken {
		public GroupToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}
	}

	public abstract class KeywordToken extends AbstractToken {

		public KeywordToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}

		@Override
		public boolean equalsOrReplacesNode(AbstractNode node) {
			return keywordSerializer.equalsOrReplacesNode(current.getDelegate(), (Keyword) getGrammarElement(), node);
		}

		@Override
		protected String serializeThisInternal(AbstractNode node) {
			return keywordSerializer.serializeUnassignedKeyword(current.getDelegate(), (Keyword) getGrammarElement(),
					node);
		}
	}

	public class RootToken extends AbstractToken {

		private RootToken(AbstractToken next, IInstanceDescription inst) {
			super(null, next, 0, inst);
		}

		public RootToken(IInstanceDescription inst) {
			super(null, null, 0, inst);
		}

		public boolean containsRuleCall() {
			return true;
		}

		@Override
		public AbstractToken createParentFollower(AbstractToken next, int actIndex, int index, IInstanceDescription i) {
			return index != 0 || !i.isConsumed() ? null : new RootToken(next, i);
		}

		@Override
		public AbstractElement getGrammarElement() {
			return null;
		}
	}

	public abstract class RuleCallToken extends AbstractToken {
		public RuleCallToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}
	}

	public abstract class UnassignedTextToken extends AbstractToken {

		public UnassignedTextToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}

		@Override
		public boolean equalsOrReplacesNode(AbstractNode node) {
			return valueSerializer.equalsOrReplacesNode(current.getDelegate(), (RuleCall) getGrammarElement(), node);
		}

		@Override
		protected String serializeThisInternal(AbstractNode node) {
			return valueSerializer
					.serializeUnassignedValue(current.getDelegate(), (RuleCall) getGrammarElement(), node);
		}
	}

	public abstract class UnorderedGroupToken extends AbstractToken {
		public UnorderedGroupToken(AbstractToken parent, AbstractToken next, int no, IInstanceDescription current) {
			super(parent, next, no, current);
		}
	}

	protected class WsMergerStream {
		protected CompositeNode lastCont = null;
		protected int lastIndex = 0;
		protected ITokenStream out;

		public WsMergerStream(ITokenStream out) {
			super();
			this.out = out;
		}

		public void flush() throws IOException {
			CompositeNode c = lastCont;
			int i = lastIndex;
			List<LeafNode> ws = Lists.newArrayList();
			while (true) {
				i++;
				while (c != null && i >= c.getChildren().size()) {
					i = c.getParent() != null ? c.getParent().getChildren().indexOf(c) + 1 : -1;
					c = c.getParent();
				}
				while (c != null && c.getChildren().get(i) instanceof CompositeNode) {
					c = (CompositeNode) c.getChildren().get(i);
					i = 0;
				}
				if (c == null) {
					for (LeafNode l : ws) {
						//						System.out.println("WS: '" + l.getText() + "'");
						out.writeHidden(l.getGrammarElement(), l.getText());
					}
					out.flush();
					return;
				}
				AbstractNode n = c.getChildren().get(i);
				if (tokenUtil.isToken(n)) {
					out.flush();
					return;
				} else if (tokenUtil.isWhitespaceNode(n))
					ws.add((LeafNode) n);
			}

		}

		protected void setNext() {

		}

		public void writeComment(LeafNode comment) throws IOException {
			writeWhitespacesSince(comment);
			//			System.out.println("CM: '" + comment.getText() + "'");
			out.writeHidden(comment.getGrammarElement(), comment.getText());
		}

		public void writeSemantic(AbstractElement grammarElement, String value, AbstractNode node) throws IOException {
			writeWhitespacesSince(node);
			//			System.out.println("S:  '" + value + "'");
			out.writeSemantic(grammarElement, value);
		}

		protected void writeWhitespacesSince(AbstractNode node) throws IOException {
			if (node == null) {
				lastCont = null;
				return;
			}
			CompositeNode c = lastCont;
			int i = lastIndex;
			lastCont = node.getParent();
			lastIndex = lastCont.getChildren().indexOf(node);
			List<LeafNode> ws = Lists.newArrayList();
			while (true) {
				i++;
				while (c != null && i >= c.getChildren().size()) {
					i = c.getParent() != null ? c.getParent().getChildren().indexOf(c) + 1 : -1;
					c = c.getParent();
				}
				while (c != null && c.getChildren().size() > 0 && c.getChildren().get(i) != node
						&& c.getChildren().get(i) instanceof CompositeNode) {
					c = (CompositeNode) c.getChildren().get(i);
					i = 0;
				}
				if (c == null)
					return;
				if (c.getChildren().size() == 0)
					continue;
				AbstractNode n = c.getChildren().get(i);
				if (n == node) {
					if (n instanceof CompositeNode)
						for (LeafNode l : n.getLeafNodes())
							if (tokenUtil.isWhitespaceNode(l))
								ws.add(l);
							else
								break;
					if (ws.isEmpty()) {
						out.writeHidden(hiddenTokenHelper.getWhitespaceRuleFor(""), "");
						//						System.out.println("WS: -nothing-");
					}
					for (LeafNode l : ws) {
						//						System.out.println("WS: '" + l.getText() + "'");
						out.writeHidden(l.getGrammarElement(), l.getText());
					}
					return;
				} else if (tokenUtil.isWhitespaceNode(n))
					ws.add((LeafNode) n);
				else
					return;
			}
		}
	}

	@Inject
	protected ICommentAssociater commentAssociater;

	@Inject
	protected ICrossReferenceSerializer crossRefSerializer;

	@Inject
	protected IEnumLiteralSerializer enumLitSerializer;

	@Inject
	protected IHiddenTokenHelper hiddenTokenHelper;

	@Inject
	protected IKeywordSerializer keywordSerializer;

	private final Logger log = Logger.getLogger(AbstractParseTreeConstructor.class);

	@Inject
	protected TokenUtil tokenUtil;

	@Inject
	protected ITransientValueService tvService;

	@Inject
	protected IValueSerializer valueSerializer;

	protected void assignComment(LeafNode comment, Map<EObject, AbstractToken> eObject2Token,
			Map<LeafNode, EObject> comments) {
		EObject container = comments.get(comment);
		if (container == null)
			return;
		AbstractToken token = eObject2Token.get(container);
		if (token != null) {
			for (int i = 0; i < token.getChildren().size(); i++) {
				AbstractToken t = token.getChildren().get(i);
				if ((t instanceof KeywordToken || t instanceof AssignmentToken) && t.getNode() == null) {
					token.getChildren().add(i, new CommentToken(comment));
					return;
				}
			}
			token.getChildren().add(new CommentToken(comment));
		}
	}

	protected void assignNodesByMatching(Map<EObject, AbstractToken> eObject2Token, CompositeNode node,
			Map<LeafNode, EObject> comments) throws IOException {
		TreeIterator<EObject> i = node.eAllContents();
		while (i.hasNext()) {
			EObject o = i.next();
			if (!(o instanceof AbstractNode))
				continue;
			AbstractNode n = (AbstractNode) o;
			AbstractRule r = n.getGrammarElement() instanceof AbstractRule ? (AbstractRule) n.getGrammarElement()
					: null;
			if (hiddenTokenHelper.isWhitespace(r))
				continue;
			else if (n instanceof LeafNode && hiddenTokenHelper.isComment(r))
				assignComment((LeafNode) n, eObject2Token, comments);
			else if (tokenUtil.isToken(n)) {
				assignTokenByMatcher(n, eObject2Token);
				i.prune();
				CompositeNode p = n.getParent();
				while (p != null && assignTokenDirect(p, eObject2Token))
					p = p.getParent();
			}
		}
	}

	protected void assignTokenByMatcher(AbstractNode node, AbstractToken token, boolean rec) {
		for (AbstractToken t : token.getChildren())
			if (rec && t instanceof AssignmentToken)
				return;
			else if (t.getNode() == null && t.equalsOrReplacesNode(node)) {
				t.setNode(node);
				return;
			} else if (node.getGrammarElement() instanceof Keyword && t instanceof ActionToken)
				assignTokenByMatcher(node, t, true);
		return;
	}

	protected void assignTokenByMatcher(AbstractNode node, Map<EObject, AbstractToken> eObject2Token) {
		EObject owner = tokenUtil.getTokenOwner(node);
		if (owner == null)
			return;
		AbstractToken token = eObject2Token.get(owner);
		if (token != null)
			assignTokenByMatcher(node, token, false);
	}

	protected boolean assignTokenDirect(AbstractNode node, Map<EObject, AbstractToken> eObject2Token) {
		if (node.getElement() == null)
			return true;
		AbstractToken token = eObject2Token.get(node.getElement());
		if (token != null && token.getNode() == null) {
			token.setNode(node);
			return true;
		}
		return false;
	}

	protected void collectRootsAndEObjects(AbstractToken token, Map<EObject, AbstractToken> obj2token,
			Set<CompositeNode> roots) {
		CompositeNode node = NodeUtil.getNode(token.getCurrent().getDelegate());
		if (node != null) {
			while (node.eContainer() != null)
				node = node.getParent();
			roots.add(node);
		}
		if (!token.getChildren().isEmpty()) {
			obj2token.put(token.getChildren().get(0).getCurrent().getDelegate(), token);
			for (AbstractToken t : token.getChildren())
				if (!t.getChildren().isEmpty())
					collectRootsAndEObjects(t, obj2token, roots);
		}
	}

	protected TreeConstructionReportImpl createReport(EObject root) {
		return new TreeConstructionReportImpl(root);
	}

	protected String debug(AbstractToken t, IInstanceDescription i) {
		StringBuffer b = new StringBuffer(t.serialize(10, 50, true));
		b.append(t.getClass().getSimpleName() + ":" + t.getNo() + " -> " + i);
		return b.toString();
	}

	protected void dump(String ident, AbstractToken token) {
		System.out.println(ident + "begin " + token.getClass().getSimpleName() + " - "
				+ EmfFormatter.objPath(token.getCurrent().getDelegate()) + " node:" + dumpNode(token.getNode()));
		String i = ident + "\t";
		for (AbstractToken t : token.getChildren()) {
			if (t.getChildren().isEmpty())
				System.out.println(i + " -> " + t.getClass().getSimpleName() + " - "
						+ (t.getCurrent() == null ? "null" : EmfFormatter.objPath(t.getCurrent().getDelegate()))
						+ " node:" + dumpNode(t.getNode()));
			else
				dump(i, t);
		}
		System.out.println(ident + "end");
	}

	protected String dumpNode(AbstractNode node) {
		if (node == null)
			return "null";
		return node.eClass().getName() + "'" + node.serialize().replace('\n', ' ') + "' "
				+ Integer.toHexString(node.hashCode());
	}

	protected IInstanceDescription getDescr(EObject obj) {
		return new InstanceDescription(tvService, obj);
	}

	public abstract IGrammarAccess getGrammarAccess();

	protected abstract AbstractToken getRootToken(IInstanceDescription inst);

	protected AbstractToken serialize(EObject object, AbstractToken f, TreeConstructionReportImpl rep) {
		if (object == null)
			throw new NullPointerException("The to-be-serialized EObject is null");
		IInstanceDescription inst = f.getCurrent();
		int no = 0;
		boolean lastSucc = true;
		while (f != null) {
			AbstractToken n = null;
			IInstanceDescription i = null;
			if ((n = f.createFollower(no, inst)) != null) {
				while (n != null && (i = n.tryConsume()) == null)
					n = f.createFollower(++no, inst);
			}
			if (n instanceof RootToken && n.getNext() != null)
				return n.getNext();
			if (n != null && i != null) {
				if (log.isTraceEnabled())
					log.trace(debug(f, inst) + " -> found -> " + f.serializeThis(null));
				f = n;
				inst = i;
				no = 0;
				lastSucc = true;
			} else {
				if (log.isTraceEnabled())
					log.trace(debug(f, inst) + " -> fail -> " + (f.getNo() + 1));
				if (lastSucc)
					rep.addDeadEnd(f);
				no = f.getNo() + 1;
				inst = f.getCurrent();
				f = f.getNext();
				lastSucc = false;
			}
		}
		throw new XtextSerializationException(rep, "Serialization failed");
	}

	protected AbstractToken serialize(EObject object, TreeConstructionReportImpl rep) {
		if (object == null)
			throw new NullPointerException("The to-be-serialized EObject is null");
		AbstractToken root = getRootToken(getDescr(object));
		AbstractToken first = serialize(object, root, rep);
		Map<EObject, List<AbstractToken>> tree = Maps.newHashMap();
		AbstractToken t = first;
		while (t != null) {
			List<AbstractToken> l = tree.get(t.getCurrent().getDelegate());
			if (l == null)
				tree.put(t.getCurrent().getDelegate(), l = Lists.newArrayList());
			if (t.getParent() != null)
				l.add(t);
			if (t.getNext() != null) {
				if (t.getNext().getParent() == null)
					root.children = l;
				else if (t.getNext().getCurrent().getDelegate() == t.getCurrent().getDelegate().eContainer())
					t.getNext().children = l;
			}
			t = t.getNext();
		}
		return root;
	}

	public TreeConstructionReport serializeRecursive(EObject object, ITokenStream out) throws IOException {
		TreeConstructionReportImpl rep = createReport(object);
		AbstractToken root = serialize(object, rep);
		Set<CompositeNode> roots = Sets.newHashSet();
		Map<EObject, AbstractToken> obj2token = Maps.newHashMap();
		collectRootsAndEObjects(root, obj2token, roots);
		//		dump("", root);
		Map<LeafNode, EObject> comments = commentAssociater.associateCommentsWithSemanticEObjects(object, roots);
		for (CompositeNode r : roots)
			assignNodesByMatching(obj2token, r, comments);
		WsMergerStream wsout = new WsMergerStream(out);
		//		dump("", root);
		write(root, wsout);
		wsout.flush();
		return rep;
	}

	protected void write(AbstractToken token, WsMergerStream out) throws IOException {
		if (!token.getChildren().isEmpty())
			for (AbstractToken t : token.getChildren())
				write(t, out);
		else {
			if (token instanceof CommentToken)
				out.writeComment((LeafNode) token.getNode());
			else {
				String val = token.serializeThis(token.getNode());
				if (val != null) {
					if (token instanceof AssignmentToken)
						out.writeSemantic(((AssignmentToken) token).getAssignmentElement(), val, token.getNode());
					else
						out.writeSemantic(token.getGrammarElement(), val, token.getNode());
				}
			}
		}
	}

	protected void writeComments(Iterable<LeafNode> comments, WsMergerStream out, Set<AbstractNode> consumedComments)
			throws IOException {
		for (LeafNode c : comments)
			if (consumedComments.add(c))
				out.writeComment(c);
	}
}
