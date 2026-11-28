parser grammar ForgeParser;
options { tokenVocab=ForgeLexer; }

root: importDecl* (
constDef
| record
| enum
| function
| nodeDef
| nodeInterfaceImpl
| synDef
| inhDef
| inhImpl
| coll
| contrib
| parserDef
| parseRuleDef
| circularDef
| portDef
| graphDef
| flowDef
| useDecl
| modDef
| traitDef
)* EOF;

// mod jvm { ... }   -- a module: its declarations are named jvm::Insn outside of it (or Insn after `use jvm`)
modDef: MOD name=IDENT LBRACE (
constDef
| record
| enum
| function
| nodeDef
| nodeInterfaceImpl
| synDef
| inhDef
| inhImpl
| coll
| contrib
| parserDef
| parseRuleDef
| circularDef
| portDef
| graphDef
| flowDef
| useDecl
| modDef
| traitDef
)* RBRACE;

// use jvm   -- the declarations of module jvm can be used without jvm::   /   use jvm::Insn   -- just this one
useDecl: USE path=IDENT;

// trait Ord { op fun compareTo(other: Self): int }   -- what a type parameter can require: fun <T: Ord> ...
// an `op fun` member is an operator on values of the type parameter (`a < b`); the type needs an `op fun` for it
traitDef: TRAIT name=IDENT LBRACE traitMember* RBRACE;
traitMember: (op=IDENT)? (FUN | property=VAL) name=IDENT (params=LPAREN (parameter (COMMA parameter)*)? RPAREN)? COLON ty=type;

// <T, U: Ord & Show>   -- type parameters; a bound is a trait or a type (T: Expr)
typeParams: LT typeParam (COMMA typeParam)* GT;
typeParam: name=IDENT (COLON bounds+=type (IAND bounds+=type)*)?;

// import "jvm.forge"   -- the declarations of another file (relative to this one) are part of this program
importDecl: IMPORT path=STRING;

// const greeting = "hello"   /   const answer: int = 42   -- inlined wherever it is used
constDef: CONST name=IDENT (COLON ty=type)? ASSIGN value=expression;

parameter: name=IDENT COLON ty=type;

record: RECORD name=IDENT typeParams? LBRACE (recordField (COMMA recordField)*)? RBRACE;
recordField: name=IDENT COLON ty=type;
// `op fun T.plus(other: T): T` defines an operator for T; `op` is only a keyword here
// `fun <T> List<T>.distinct(): List<T>`: type parameters, inferred at each call
// `val Rec.name: T = ...`: a property of records, enums and the builtin types, read like a field; a method
// without parameters otherwise (nodes have syn attributes instead)
function: (ext=EXT)? (op=IDENT)? (FUN | property=VAL) typeParams? (receiver=type DOT)? name=IDENT
    (params=LPAREN (parameter (COMMA parameter)*)? RPAREN)? COLON ty=type (ASSIGN expression)?;

enum: ENUM name=IDENT typeParams? LBRACE (enumItem (COMMA enumItem)*)? RBRACE;
enumItem: name=IDENT (LPAREN (parameter (COMMA parameter)*)? RPAREN)?;

simpleParameterPattern:
    name=IDENT # definedParameter
    | UNDERSCORE # undefinedParameter;

// `node CompoundAssign(...): Stmt by Assign(...)`: forwarding. The attributes of Stmt (and of its interfaces) the
// node has no equation for are those of the tree after `by`, a child of the node; `by` is only a keyword here
nodeDef: NODE name=IDENT (LPAREN (parameter (COMMA parameter)*)? RPAREN)? (COLON parent=IDENT forwarding?)?;
nodeInterfaceImpl: FOR extended=IDENT IMPL iface=IDENT forwarding?;
forwarding: by=IDENT forward=expression;

// `child syn Program.builtins: List<Decl> = ...`: the value is a tree that becomes a child of the node (a
// nonterminal attribute); `child` is only a keyword here
synDef: (modifier=IDENT)? SYN node=IDENT DOT attrName=IDENT COLON attrType=type (ASSIGN syn=expression)?;

inhDef: INH attrName=IDENT COLON attrType=type ASSIGN init=expression;
inhImpl: INH node=IDENT DOT attrName=IDENT LBRACE inhDelegate* RBRACE;
inhDelegate: child=IDENT (LBRACKET key=expression RBRACKET)? RARROW value=expression;

// circular(init) Node.attr: Type = body   -- fixpoint attribute
circularDef: CIRCULAR LPAREN init=expression RPAREN node=IDENT DOT attrName=IDENT COLON attrType=type ASSIGN body=expression;

// port onTrue, onFalse   /   inh port break   (inherited ports are looked up through enclosing flows)
portDef: (inherited=INH)? PORT IDENT (COMMA IDENT)*;

// graph cfg: Function   -- one graph per Function node, built from the flows of the nodes below it
graphDef: GRAPH name=IDENT COLON rootNode=IDENT;

// flow cfg: While(cond, body) { entry(cond); onTrue(cond) -> body; onFalse(cond) -> next; body -> cond }
flowDef: FLOW graph=IDENT COLON node=IDENT (LPAREN (flowBinder (COMMA flowBinder)*)? RPAREN)? flowBlock;
flowBinder: name=IDENT | UNDERSCORE;
flowBlock: LBRACE (flowStmt SEMI?)* RBRACE;
flowStmt
    : source=expression ARROW target=expression (WITH label=expression)?    # flowConnect
    | VERTEX name=IDENT (ASSIGN label=expression)?                          # flowVertex
    | IF cond=expression then=flowBlock (ELSE alt=flowBlock)?               # flowIf
    | FOR name=IDENT IN iterable=expression flowBlock                       # flowFor
    | expression                                                            # flowCall
    ;

coll: COLL node=IDENT DOT collName=IDENT COLON collType=type ASSIGN init=expression;
contrib: CONTRIB node=IDENT ARROW receiver=expression DOT collName=IDENT (AS alias=IDENT)? ASSIGN value=expression;

// parser Lang skip '\s+' keywords 'if' 'while'   /   parser ClassFile binary
parserDef: PARSER name=IDENT parserOption*;
parserOption: key=IDENT REGEX_STRING*;

parseRuleDef
    : RULE parserName=IDENT DOT ruleName=IDENT COLON returnType=type operatorSpec? (ARROW parserRuleOption (IOR parserRuleOption)*)?
    ;

// an operator of an abstract rule: @prefix(30), @infixLeft(10), @infixRight(40), @postfix(50)
operatorSpec: AT kind=IDENT LPAREN (negative=MINUS)? precedence=INT RPAREN;

parserRuleOption
    : parseRule (LBRACE producer=expression RBRACE)?
    ;

parseRule
    : parserAlternative
    ;

parserAlternative
    : parserSequence (IOR parserSequence)*
    ;

parserSequence
    : parserLabeled+
    ;

parserLabeled
    : (label=IDENT COLON)? (predicate=(EXCLAMATIONMARK | IAND))? parserPostfix
    ;

parserPostfix
    : atom=parserAtom (op=(QUESTIONMARK | PLUS | STAR) | LBRACKET count=expression RBRACKET)?
    ;

parserAtom
    : terminalRule
    | bitsRule
    | builtinRule
    | nonterminalRule
    | LPAREN parseRule RPAREN
    ;

terminalRule: REGEX_STRING;
// exact bit patterns of binary parsers: 0xCAFEBABE, 0b0101
bitsRule: HEX | BIN;
// primitives of binary parsers: @u1 @u2 @u4 @bit @uint(n) @sint(n) @bytes(n)
builtinRule: AT name=IDENT (LPAREN arg=expression RPAREN)?;
nonterminalRule: name=IDENT;

type: LIST LT type GT # listType
    | SET LT type GT # setType
    | MAP LT key=type COMMA value=type GT # mapType
    | name=IDENT (LT args+=type (COMMA args+=type)* GT)? # namedType
    // (int, str) -> bool
    | LPAREN (params+=type (COMMA params+=type)*)? RPAREN ARROW ret=type # functionType
    // (int, str)
    | LPAREN elems+=type COMMA elems+=type (COMMA elems+=type)* RPAREN # tupleType
    | LPAREN type RPAREN # parenType
    | ty=type QUESTIONMARK # optionalType;

expression
    : value=INT                                                              # intValue
    | value=LONG                                                             # longValue
    | value=FLOAT                                                            # floatValue
    | value=STRING                                                           # stringValue
    | (TRUE | FALSE)                                                         # boolValue
    | NULL                                                                   # nullValue
    | THIS                                                                   # thisExpr
    | LPAREN expression RPAREN                                               # enclosed
    // (1, "a")
    | LPAREN expression COMMA expression (COMMA expression)* RPAREN          # tupleLiteral
    // { x, y -> x + y }   /   { x: int -> x * 2 }   /   { -> 0 }
    | LBRACE (lambdaParam (COMMA lambdaParam)*)? ARROW body=expression RBRACE  # lambda
    | LBRACKET lo=expression COLON hi=expression RBRACKET                   # rangeLiteral
    | LBRACKET (expression (COMMA expression)*)? RBRACKET                    # listLiteral
    | LBRACE COLON RBRACE                                                    # emptyMapLiteral
    | LBRACE expression COLON expression (COMMA expression COLON expression)* RBRACE  # mapLiteral
    | LBRACE (expression (COMMA expression)*)? RBRACE                        # setLiteral
    | MATCH switch=expression LBRACE (matchCase)* (UNDERSCORE ARROW alt=expression)? RBRACE  # match
    // f(x), f(x) { y -> ... }, f { ... }: a lambda after the call is its last argument
    | name=IDENT LPAREN (expression (COMMA expression)*)? RPAREN trailingLambda?  # functionCall
    | name=IDENT trailingLambda                                              # functionCall
    | IDENT                                                                  # identifier

    // postfix; `a?.b` (safe=SAFE_DOT) is null if `a` is
    | receiver=expression (DOT | safe=SAFE_DOT) name=IDENT LPAREN (expression (COMMA expression)*)? RPAREN trailingLambda?  # methodCall
    | receiver=expression (DOT | safe=SAFE_DOT) name=IDENT trailingLambda    # methodCall
    | receiver=expression (DOT | safe=SAFE_DOT) name=IDENT                   # fieldAccess
    // t.0; `t.0.1` is lexed with a float
    | receiver=expression DOT (index=INT | path=FLOAT)                        # tupleAccess
    // calls a function value: (f)(x), this.handler(x), make()(x)
    | callee=expression LPAREN (expression (COMMA expression)*)? RPAREN      # valueCall
    | container=expression LBRACKET lo=expression? COLON hi=expression? RBRACKET  # sliceAccess
    | container=expression LBRACKET key=expression RBRACKET                  # containerAccess

    // prefix unary: binds tighter than binary ops, looser than postfix
    | MINUS expression                                                       # negation
    | EXCLAMATIONMARK expression                                             # inversion

    | left=expression op=(STAR | SLASH | PERCENT) right=expression           # mulDivMod
    | left=expression op=(PLUS | MINUS) right=expression                     # addSub
    // << >> >>>: adjacent angle brackets, so that types like List<List<int>> still close with two GT tokens
    | left=expression (LT LT | GT GT GT | GT GT) right=expression            # shift
    | left=expression CONCAT right=expression                                # concat
    | left=expression op=(LT | GT | LEQ | GEQ) right=expression              # relational
    | left=expression op=(IN | NOTIN) right=expression                       # inOp
    | left=expression op=(EQUALS | NOTEQUALS) right=expression               # equality
    | left=expression IAND right=expression                                  # iand
    | left=expression IOR right=expression                                   # ior
    | left=expression AND right=expression                                   # logicalAnd
    | left=expression XOR right=expression                                   # logicalXor
    | left=expression OR right=expression                                    # logicalOr
    | expr=expression ELVIS alt=expression                                   # elvis

    | value=expression NOTNULL                                               # nullCheck
    | value=expression AS ty=type                                            # cast
    | value=expression IS ty=type                                            # instanceOf
    // x * y where x = 1, y: int = 2
    | expr=expression WHERE binding (COMMA binding)*                         # whereExpr

    // loosest: right-extending constructs
    | IF cond=expression THEN then=expression ELSE alt=expression            # ifElse
    ;

lambdaParam: (name=IDENT | UNDERSCORE) (COLON ty=type)?;

// the lambda after a call: `xs.map { x -> x * 2 }`; without `->` it has the parameter `it` (or none): `xs.map { it * 2 }`.
// After parsing it becomes a lambda argument of the call (Interpolation.kt).
trailingLambda: LBRACE ((lambdaParam (COMMA lambdaParam)*)? ARROW)? body=expression RBRACE;

// x: int = 1   /   (a, b) = pair
binding: (name=IDENT | LPAREN names+=IDENT (COMMA names+=IDENT)+ RPAREN) (COLON ty=type)? ASSIGN value=expression;

matchCase: pattern (IF cond=expression)? ARROW value=expression;

pattern: typePattern | splitPattern | listPattern | tuplePattern | exprPattern;
patternArg
    : typePattern                                              # typePatternArg
    | wildcardPattern                                          # wildcardPatternArg
    | patternVariable                                          # patternVariableArg
    | listPattern                                              # listPatternArg
    | tuplePattern                                             # tuplePatternArg
    | exprPattern                                               # expressionArg
    | <assoc=right> element=patternArg SPLIT rest=patternArg    # splitPatternArg
    ;

typePattern: receiver=IDENT DOT name=IDENT (LPAREN (patternArg (COMMA patternArg)*)? RPAREN)?;
patternVariable: name=IDENT;
wildcardPattern: UNDERSCORE;
splitPattern: element=patternArg SPLIT rest=patternArg;
listPattern: LBRACKET (patternArg (COMMA patternArg)*)? RBRACKET;
tuplePattern: LPAREN patternArg (COMMA patternArg)+ RPAREN;
exprPattern: expression;
