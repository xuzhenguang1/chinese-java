/*
 * Copyright (c) 1999, 2025, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.tools.javac.parser;

import java.util.HashMap;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.Map;

import com.sun.tools.javac.api.Formattable;
import com.sun.tools.javac.api.Messages;
import com.sun.tools.javac.parser.Tokens.Token.Tag;
import com.sun.tools.javac.util.*;

/** A class that defines codes/utilities for Java source tokens
 *  returned from lexical analysis.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Tokens {

    private final Names names;

    /**
     * Keyword array. Maps name indices to Token.
     */
    private Map<String, TokenKind> keywords = new HashMap<>();

    /**
     * CJC 原生补丁：中文关键字别名（中文 → 英文关键字名）。
     * 只追加、不替换，英文关键字与中文关键字双语并存。
     * 下方别名块由 工具\cjc\同步词表.ps1 从 关键字表.txt + 近义词典.txt 生成，勿手改。
     */
    private static final Map<String, String> CJC_KEYWORDS = Map.ofEntries(
            // >>> 自动生成：CJC_KEYWORDS（来源 工具\cjc\关键字表.txt + 近义词典.txt，勿手改）
            Map.entry("类", "class"),
            Map.entry("类型", "class"),
            Map.entry("接口", "interface"),
            Map.entry("接口类型", "interface"),
            Map.entry("枚举", "enum"),
            Map.entry("枚举型", "enum"),
            Map.entry("包", "package"),
            Map.entry("包声明", "package"),
            Map.entry("导入", "import"),
            Map.entry("引入", "import"),
            Map.entry("新建", "new"),
            Map.entry("实例化", "new"),
            Map.entry("创建", "new"),
            Map.entry("继承", "extends"),
            Map.entry("扩展", "extends"),
            Map.entry("实现", "implements"),
            Map.entry("实现接口", "implements"),
            Map.entry("这", "this"),
            Map.entry("当前对象", "this"),
            Map.entry("这个", "this"),
            Map.entry("父", "super"),
            Map.entry("超类", "super"),
            Map.entry("父类", "super"),
            Map.entry("公有", "public"),
            Map.entry("公开", "public"),
            Map.entry("公共", "public"),
            Map.entry("私有", "private"),
            Map.entry("私密", "private"),
            Map.entry("私用", "private"),
            Map.entry("保护", "protected"),
            Map.entry("受保护", "protected"),
            Map.entry("防护", "protected"),
            Map.entry("静态", "static"),
            Map.entry("静止", "static"),
            Map.entry("最终", "final"),
            Map.entry("终态", "final"),
            Map.entry("不可变", "final"),
            Map.entry("最后", "final"),
            Map.entry("抽象", "abstract"),
            Map.entry("同步", "synchronized"),
            Map.entry("互斥", "synchronized"),
            Map.entry("协同", "synchronized"),
            Map.entry("原生", "native"),
            Map.entry("本地", "native"),
            Map.entry("易失", "volatile"),
            Map.entry("易变", "volatile"),
            Map.entry("瞬态", "transient"),
            Map.entry("瞬变", "transient"),
            Map.entry("严格浮点", "strictfp"),
            Map.entry("精确浮点", "strictfp"),
            Map.entry("若", "if"),
            Map.entry("如果", "if"),
            Map.entry("假如", "if"),
            Map.entry("若是", "if"),
            Map.entry("倘若", "if"),
            Map.entry("要是", "if"),
            Map.entry("否则", "else"),
            Map.entry("不然", "else"),
            Map.entry("要不然", "else"),
            Map.entry("对于", "for"),
            Map.entry("遍历", "for"),
            Map.entry("当", "while"),
            Map.entry("每当", "while"),
            Map.entry("做", "do"),
            Map.entry("执行一次", "do"),
            Map.entry("切换", "switch"),
            Map.entry("分支", "switch"),
            Map.entry("情形", "case"),
            Map.entry("情况", "case"),
            Map.entry("默认", "default"),
            Map.entry("预设", "default"),
            Map.entry("跳出", "break"),
            Map.entry("中断", "break"),
            Map.entry("继续", "continue"),
            Map.entry("下一个", "continue"),
            Map.entry("返回", "return"),
            Map.entry("回传", "return"),
            Map.entry("回来", "return"),
            Map.entry("是", "instanceof"),
            Map.entry("属于", "instanceof"),
            Map.entry("尝试", "try"),
            Map.entry("试验", "try"),
            Map.entry("捕获", "catch"),
            Map.entry("捕捉", "catch"),
            Map.entry("抛出", "throw"),
            Map.entry("抛出异常", "throw"),
            Map.entry("声明抛出", "throws"),
            Map.entry("声明抛出异常", "throws"),
            Map.entry("兜底", "finally"),
            Map.entry("收尾", "finally"),
            Map.entry("断言", "assert"),
            Map.entry("断言检查", "assert"),
            Map.entry("整数", "int"),
            Map.entry("整型", "int"),
            Map.entry("长整数", "long"),
            Map.entry("长整型", "long"),
            Map.entry("短整数", "short"),
            Map.entry("短整型", "short"),
            Map.entry("字节", "byte"),
            Map.entry("字节型", "byte"),
            Map.entry("浮点", "float"),
            Map.entry("浮点型", "float"),
            Map.entry("双浮点", "double"),
            Map.entry("双浮点型", "double"),
            Map.entry("字符", "char"),
            Map.entry("单字符", "char"),
            Map.entry("布尔", "boolean"),
            Map.entry("逻辑型", "boolean"),
            Map.entry("无", "void"),
            Map.entry("无返回值", "void"),
            Map.entry("空", "null"),
            Map.entry("空值", "null"),
            Map.entry("真", "true"),
            Map.entry("成立", "true"),
            Map.entry("假", "false"),
            Map.entry("不成立", "false")
            // <<< 自动生成：CJC_KEYWORDS
            );

    public static final Context.Key<Tokens> tokensKey = new Context.Key<>();

    public static Tokens instance(Context context) {
        Tokens instance = context.get(tokensKey);
        if (instance == null)
            instance = new Tokens(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected Tokens(Context context) {
        context.put(tokensKey, this);
        names = Names.instance(context);
        for (TokenKind t : TokenKind.values()) {
            if (t.name != null) {
                names.fromString(t.name);
                keywords.put(t.name, t);
            }
        }
        // CJC 原生补丁：追加中文别名，映射到与英文关键字相同的 TokenKind
        for (Map.Entry<String, String> e : CJC_KEYWORDS.entrySet()) {
            TokenKind t = keywords.get(e.getValue());
            if (t == null) {
                throw new AssertionError("CJC 关键字表引用了未知的英文关键字：" + e.getValue());
            }
            keywords.put(e.getKey(), t);
        }
    }

    /**
     * Create a new token given a name; if the name corresponds to a token name,
     * a new token of the corresponding kind is returned; otherwise, an
     * identifier token is returned.
     */
    TokenKind lookupKind(Name name) {
        TokenKind t = keywords.get(name.toString());
        return (t != null) ? t : TokenKind.IDENTIFIER;
    }

    TokenKind lookupKind(String name) {
        TokenKind t = keywords.get(name);
        return (t != null) ? t : TokenKind.IDENTIFIER;
    }

    /**
     * This enum defines all tokens used by the javac scanner. A token is
     * optionally associated with a name.
     */
    public enum TokenKind implements Formattable, Predicate<TokenKind> {
        EOF(),
        ERROR(),
        IDENTIFIER(Tag.NAMED),
        ABSTRACT("abstract"),
        ASSERT("assert", Tag.NAMED),
        BOOLEAN("boolean", Tag.NAMED),
        BREAK("break"),
        BYTE("byte", Tag.NAMED),
        CASE("case"),
        CATCH("catch"),
        CHAR("char", Tag.NAMED),
        CLASS("class"),
        CONST("const"),
        CONTINUE("continue"),
        DEFAULT("default"),
        DO("do"),
        DOUBLE("double", Tag.NAMED),
        ELSE("else"),
        ENUM("enum", Tag.NAMED),
        EXTENDS("extends"),
        FINAL("final"),
        FINALLY("finally"),
        FLOAT("float", Tag.NAMED),
        FOR("for"),
        GOTO("goto"),
        IF("if"),
        IMPLEMENTS("implements"),
        IMPORT("import"),
        INSTANCEOF("instanceof"),
        INT("int", Tag.NAMED),
        INTERFACE("interface"),
        LONG("long", Tag.NAMED),
        NATIVE("native"),
        NEW("new"),
        PACKAGE("package"),
        PRIVATE("private"),
        PROTECTED("protected"),
        PUBLIC("public"),
        RETURN("return"),
        SHORT("short", Tag.NAMED),
        STATIC("static"),
        STRICTFP("strictfp"),
        SUPER("super", Tag.NAMED),
        SWITCH("switch"),
        SYNCHRONIZED("synchronized"),
        THIS("this", Tag.NAMED),
        THROW("throw"),
        THROWS("throws"),
        TRANSIENT("transient"),
        TRY("try"),
        VOID("void", Tag.NAMED),
        VOLATILE("volatile"),
        WHILE("while"),
        INTLITERAL(Tag.NUMERIC),
        LONGLITERAL(Tag.NUMERIC),
        FLOATLITERAL(Tag.NUMERIC),
        DOUBLELITERAL(Tag.NUMERIC),
        CHARLITERAL(Tag.NUMERIC),
        STRINGLITERAL(Tag.STRING),
        STRINGFRAGMENT(Tag.STRING),
        TRUE("true", Tag.NAMED),
        FALSE("false", Tag.NAMED),
        NULL("null", Tag.NAMED),
        UNDERSCORE("_", Tag.NAMED),
        ARROW("->"),
        COLCOL("::"),
        LPAREN("("),
        RPAREN(")"),
        LBRACE("{"),
        RBRACE("}"),
        LBRACKET("["),
        RBRACKET("]"),
        SEMI(";"),
        COMMA(","),
        DOT("."),
        ELLIPSIS("..."),
        EQ("="),
        GT(">"),
        LT("<"),
        BANG("!"),
        TILDE("~"),
        QUES("?"),
        COLON(":"),
        EQEQ("=="),
        LTEQ("<="),
        GTEQ(">="),
        BANGEQ("!="),
        AMPAMP("&&"),
        BARBAR("||"),
        PLUSPLUS("++"),
        SUBSUB("--"),
        PLUS("+"),
        SUB("-"),
        STAR("*"),
        SLASH("/"),
        AMP("&"),
        BAR("|"),
        CARET("^"),
        PERCENT("%"),
        LTLT("<<"),
        GTGT(">>"),
        GTGTGT(">>>"),
        PLUSEQ("+="),
        SUBEQ("-="),
        STAREQ("*="),
        SLASHEQ("/="),
        AMPEQ("&="),
        BAREQ("|="),
        CARETEQ("^="),
        PERCENTEQ("%="),
        LTLTEQ("<<="),
        GTGTEQ(">>="),
        GTGTGTEQ(">>>="),
        MONKEYS_AT("@"),
        CUSTOM;

        public final String name;
        public final Tag tag;

        TokenKind() {
            this(null, Tag.DEFAULT);
        }

        TokenKind(String name) {
            this(name, Tag.DEFAULT);
        }

        TokenKind(Tag tag) {
            this(null, tag);
        }

        TokenKind(String name, Tag tag) {
            this.name = name;
            this.tag = tag;
        }

        public String toString() {
            switch (this) {
            case IDENTIFIER:
                return "token.identifier";
            case CHARLITERAL:
                return "token.character";
            case STRINGLITERAL:
                return "token.string";
            case INTLITERAL:
                return "token.integer";
            case LONGLITERAL:
                return "token.long-integer";
            case FLOATLITERAL:
                return "token.float";
            case DOUBLELITERAL:
                return "token.double";
            case ERROR:
                return "token.bad-symbol";
            case EOF:
                return "token.end-of-input";
            case DOT: case COMMA: case SEMI: case LPAREN: case RPAREN:
            case LBRACKET: case RBRACKET: case LBRACE: case RBRACE:
                return "'" + name + "'";
            default:
                return name;
            }
        }

        public String getKind() {
            return "Token";
        }

        public String toString(Locale locale, Messages messages) {
            return name != null ? toString() : messages.getLocalizedString(locale, "compiler.misc." + toString());
        }

        @Override
        public boolean test(TokenKind that) {
            return this == that;
        }
    }

    public interface Comment {

        enum CommentStyle {
            LINE,            // starting with //   -- also known in JLS as "end-of-line comment"
            BLOCK,           // starting with /*   -- also known in JLS as "traditional comment"
            JAVADOC_LINE,    // starting with ///
            JAVADOC_BLOCK    // starting with /**
        }

        String getText();
        Comment stripIndent();
        JCDiagnostic.DiagnosticPosition getPos();
        int getSourcePos(int index);
        CommentStyle getStyle();
        boolean isDeprecated();
    }

    /**
     * This is the class representing a javac token. Each token has several fields
     * that are set by the javac lexer (i.e. start/end position, string value, etc).
     */
    public static class Token {

        /** tags constants **/
        enum Tag {
            DEFAULT,
            NAMED,
            STRING,
            NUMERIC;
        }

        /** The token kind */
        public final TokenKind kind;

        /** The start position of this token */
        public final int pos;

        /** The end position of this token */
        public final int endPos;

        /** Comment reader associated with this token */
        public final List<Comment> comments;

        Token(TokenKind kind, int pos, int endPos, List<Comment> comments) {
            this.kind = kind;
            this.pos = pos;
            this.endPos = endPos;
            this.comments = comments;
            checkKind();
        }

        Token[] split(Tokens tokens) {
            if (kind.name.length() < 2 || kind.tag != Tag.DEFAULT) {
                throw new AssertionError("Can't split" + kind);
            }

            TokenKind t1 = tokens.lookupKind(kind.name.substring(0, 1));
            TokenKind t2 = tokens.lookupKind(kind.name.substring(1));

            if (t1 == null || t2 == null) {
                throw new AssertionError("Can't split - bad subtokens");
            }
            return new Token[] {
                new Token(t1, pos, pos + t1.name.length(), comments),
                new Token(t2, pos + t1.name.length(), endPos, null)
            };
        }

        protected void checkKind() {
            if (kind.tag != Tag.DEFAULT) {
                throw new AssertionError("Bad token kind - expected " + Tag.DEFAULT);
            }
        }

        public Name name() {
            throw new UnsupportedOperationException();
        }

        public String stringVal() {
            throw new UnsupportedOperationException();
        }

        public int radix() {
            throw new UnsupportedOperationException();
        }

        /**
         * Preserve classic semantics - if multiple javadocs are found on the token
         * the last one is returned
         */
        public Comment docComment() {
            List<Comment> comments = getDocComments();
            return comments.isEmpty() ?
                    null :
                    comments.head;
        }

        /**
         * Preserve classic semantics - deprecated should be set if at least one
         * javadoc comment attached to this token contains the '@deprecated' string
         */
        public boolean deprecatedFlag() {
            for (Comment c : getDocComments()) {
                if (c.isDeprecated()) {
                    return true;
                }
            }
            return false;
        }

        private List<Comment> getDocComments() {
            if (comments == null) {
                return List.nil();
            } else {
                ListBuffer<Comment> buf = new ListBuffer<>();
                for (Comment c : comments) {
                    Comment.CommentStyle style = c.getStyle();
                    switch (style) {
                        case JAVADOC_BLOCK, JAVADOC_LINE -> buf.add(c);
                    }
                }
                return buf.toList();
            }
        }
    }

    static final class NamedToken extends Token {
        /** The name of this token */
        public final Name name;

        public NamedToken(TokenKind kind, int pos, int endPos, Name name, List<Comment> comments) {
            super(kind, pos, endPos, comments);
            this.name = name;
        }

        protected void checkKind() {
            if (kind.tag != Tag.NAMED) {
                throw new AssertionError("Bad token kind - expected " + Tag.NAMED);
            }
        }

        @Override
        public Name name() {
            return name;
        }
    }

    static class StringToken extends Token {
        /** The string value of this token */
        public final String stringVal;

        public StringToken(TokenKind kind, int pos, int endPos, String stringVal, List<Comment> comments) {
            super(kind, pos, endPos, comments);
            this.stringVal = stringVal;
        }

        protected void checkKind() {
            if (kind.tag != Tag.STRING) {
                throw new AssertionError("Bad token kind - expected " + Tag.STRING);
            }
        }

        @Override
        public String stringVal() {
            return stringVal;
        }
    }

    static final class NumericToken extends StringToken {
        /** The 'radix' value of this token */
        public final int radix;

        public NumericToken(TokenKind kind, int pos, int endPos, String stringVal, int radix, List<Comment> comments) {
            super(kind, pos, endPos, stringVal, comments);
            this.radix = radix;
        }

        protected void checkKind() {
            if (kind.tag != Tag.NUMERIC) {
                throw new AssertionError("Bad token kind - expected " + Tag.NUMERIC);
            }
        }

        @Override
        public int radix() {
            return radix;
        }
    }

    public static final Token DUMMY =
                new Token(TokenKind.ERROR, 0, 0, null);
}
