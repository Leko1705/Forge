parser grammar ForgeParser;
options { tokenVocab=ForgeLexer; }

root: (
record
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
)* EOF;

parameter: name=IDENT COLON ty=type;

record: RECORD name=IDENT LBRACE (recordField (COMMA recordField)*)? RBRACE;
recordField: name=IDENT COLON ty=type;
function: (external=EXTERNAL)? FUN (receiver=type DOT)? name=IDENT LPAREN (parameter (COMMA parameter)*)? RPAREN COLON ty=type (ASSIGN expression)?;

enum: ENUM name=IDENT LBRACE (enumItem (COMMA enumItem)*)? RBRACE;
enumItem: name=IDENT (LPAREN (parameter (COMMA parameter)*)? RPAREN)?;

simpleParameterPattern:
    name=IDENT # definedParameter
    | UNDERSCORE # undefinedParameter;

nodeDef: NODE name=IDENT (LPAREN (parameter (COMMA parameter)*)? RPAREN)? (COLON parent=IDENT)?;
nodeInterfaceImpl: FOR extended=IDENT IMPL iface=IDENT;

synDef: SYN node=IDENT DOT attrName=IDENT COLON attrType=type (ASSIGN syn=expression)?;

inhDef: INH attrName=IDENT COLON attrType=type ASSIGN init=expression;
inhImpl: INH node=IDENT DOT attrName=IDENT LBRACE inhDelegate* RBRACE;
inhDelegate: child=IDENT (LBRACKET key=expression RBRACKET)? RARROW value=expression;

coll: COLL node=IDENT DOT collName=IDENT COLON collType=type ASSIGN init=expression;
contrib: CONTRIB node=IDENT ARROW receiver=expression DOT collName=IDENT (AS alias=IDENT)? ASSIGN value=expression;

parserDef: PARSER name=IDENT (COLON parent=IDENT)? annotation*;

// `@name` or `@name(arg, ...)`; used for parser layout (`@skip`) and production precedence (`@left(10)`)
annotation: AT name=IDENT (LPAREN (annotationArg (COMMA annotationArg)*)? RPAREN)?;
annotationArg: INT | REGEX_STRING | STRING | IDENT;

parseRuleDef
    : RULE parserName=IDENT DOT ruleName=IDENT COLON returnType=type (ARROW parserRuleOption (IOR parserRuleOption)*)?
    ;

parserRuleOption
    : parseRule (LBRACE producer=expression RBRACE)? annotation*
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
    : (label=IDENT COLON)? parserPostfix
    ;

parserPostfix
    : atom=parserAtom op=(QUESTIONMARK | PLUS | STAR)?
    ;

parserAtom
    : terminalRule
    | nonterminalRule
    | LPAREN parseRule RPAREN
    ;

// 'regex' matches a regular expression, "text" matches the text literally
terminalRule: REGEX_STRING | STRING;
nonterminalRule: name=IDENT;

type: LIST LT type GT # listType
    | MAP LT key=type COMMA value=type GT # mapType
    | name=IDENT # namedType
    | ty=type QUESTIONMARK # optionalType;

expression
    : value=INT                                                              # intValue
    | value=FLOAT                                                            # floatValue
    | value=STRING                                                           # stringValue
    | (TRUE | FALSE)                                                         # boolValue
    | NULL                                                                   # nullValue
    | THIS                                                                   # thisExpr
    | LPAREN expression RPAREN                                               # enclosed
    | LBRACKET (expression (COMMA expression)*)? RBRACKET                    # listLiteral
    | LBRACE (expression COLON expression (COMMA expression COLON expression)*)? RBRACE  # mapLiteral
    | MATCH switch=expression LBRACE (matchCase)* (UNDERSCORE ARROW alt=expression)? RBRACE  # match
    | name=IDENT LPAREN (expression (COMMA expression)*)? RPAREN             # functionCall
    | IDENT                                                                  # identifier

    // postfix
    | receiver=expression DOT name=IDENT LPAREN (expression (COMMA expression)*)? RPAREN  # methodCall
    | receiver=expression DOT name=IDENT                                     # fieldAccess
    | container=expression LBRACKET key=expression RBRACKET                  # containerAccess

    // prefix unary: binds tighter than binary ops, looser than postfix
    | MINUS expression                                                       # negation
    | EXCLAMATIONMARK expression                                             # inversion

    | left=expression op=(STAR | SLASH | PERCENT) right=expression           # mulDivMod
    | left=expression op=(PLUS | MINUS) right=expression                     # addSub
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
    | expr=expression WHERE fun=function                                     # contextualized
    | put=expression FOR name=IDENT IN iterable=expression
        (WITH acc=IDENT ASSIGN accInit=expression)?                          # listComprehension

    // loosest: right-extending constructs
    | IF cond=expression THEN then=expression ELSE alt=expression            # ifElse
    ;

matchCase: pattern (IF cond=expression)? ARROW value=expression;

pattern: typePattern | splitPattern | exprPattern | listPattern;
patternArg
    : typePattern                                              # typePatternArg
    | wildcardPattern                                          # wildcardPatternArg
    | patternVariable                                          # patternVariableArg
    | listPattern                                              # listPatternArg
    | exprPattern                                               # expressionArg
    | <assoc=right> element=patternArg SPLIT rest=patternArg    # splitPatternArg
    ;

typePattern: receiver=IDENT DOT name=IDENT (LPAREN (patternArg (COMMA patternArg)*)? RPAREN)?;
patternVariable: name=IDENT;
wildcardPattern: UNDERSCORE;
splitPattern: element=patternArg SPLIT rest=patternArg;
listPattern: LBRACKET (patternArg (COMMA patternArg)*)? RBRACKET;
exprPattern: expression;
