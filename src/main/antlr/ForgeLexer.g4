lexer grammar ForgeLexer;

@members {
    /** Whether a name follows (after spaces): `mod`, `use` and `trait` are keywords only then, else names. */
    private boolean nameFollows() {
        int i = 1;
        while (_input.LA(i) == ' ' || _input.LA(i) == '\t') i++;
        if (i == 1) return false;
        int c = _input.LA(i);
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}

EXT: 'ext';
IMPORT: 'import';
CONST: 'const';
NODE: 'node';
RECORD: 'record';
ENUM: 'enum';
FUN: 'fun';
VAL: 'val';
PARSER: 'parser';
MATCH: 'match';
RULE: 'rule';
IMPL: 'impl';
FOR: 'for';
INH: 'inh';
SYN: 'syn';
IN: 'in';
NOTIN: '!in';
IF: 'if';
THEN: 'then';
THIS: 'this';
ELSE: 'else';
LIST: 'List';
SET: 'Set';
MAP: 'Map';
TRUE: 'true';
FALSE: 'false';
WITH: 'with';
WHERE: 'where';
COLL: 'coll';
CONTRIB: 'contrib';
AS: 'as';
NULL: 'null';
CIRCULAR: 'circular';
GRAPH: 'graph';
FLOW: 'flow';
PORT: 'port';
VERTEX: 'vertex';
IS: 'is';
MOD: 'mod' {nameFollows()}?;
USE: 'use' {nameFollows()}?;
TRAIT: 'trait' {nameFollows()}?;

AT: '@';

UNDERSCORE: '_';

ARROW: '->';
RARROW: '<-';

EQUALS: '==';
NOTEQUALS: '!=';
ELVIS: '?:';
SPLIT: '::';

ASSIGN : '=';

NOTNULL: '!!';
EXCLAMATIONMARK: '!';
QUESTIONMARK: '?';
DOT: '.';
COMMA: ',';
SEMI: ';';
COLON: ':';
STAR: '*';
AND: '&&';
IAND: '&';
OR: '||';
IOR: '|';
XOR: '^';
CONCAT: '++';
PLUS: '+';
MINUS: '-';
SLASH: '/';
PERCENT: '%';
LBRACE: '{'; RBRACE: '}';
LPAREN: '('; RPAREN: ')';
LBRACKET: '['; RBRACKET: ']';
LEQ: '<='; GEQ: '>=';
LT: '<'; GT: '>';

// "text ${expression} text": the expressions are parsed when the file is parsed (Interpolation.kt)
STRING
    : '"' (ESC | INTERPOLATION | '$' | ~["\\\r\n$])* '"'
    ;

// ${ ... }: balanced braces, and strings inside
fragment INTERPOLATION
    : '${' INTERPOLATED* '}'
    ;

fragment INTERPOLATED
    : ~[{}"]
    | '{' INTERPOLATED* '}'
    | '"' (ESC | INTERPOLATION | '$' | ~["\\\r\n$])* '"'
    ;

REGEX_STRING
    : '\'' (REGEX_ESC | ~['\\\r\n])* '\''
    ;

fragment ESC
    : '\\' [btnfr"'\\$]
    ;

fragment REGEX_ESC
    : '\\' .
    ;

BIN: [0][b][01]+;
OCT: [0][o][0-7]+;
HEX: [0][x][0-9a-fA-F]+;

// a name, also qualified by modules: jvm::Insn (no spaces; `head :: tail` in patterns has them)
IDENT: NAME ('::' NAME)* ;
fragment NAME: [a-zA-Z_][a-zA-Z_0-9]* ;
FLOAT : [0-9]+[.][0-9]+;
LONG : [0-9]+ [lL] ;
INT : [0-9]+ ;
WS  : [ \t\r\n]+ -> skip ;
LINE_COMMENT: '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;
