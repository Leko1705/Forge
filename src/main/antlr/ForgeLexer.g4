lexer grammar ForgeLexer;

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
MOD: 'mod';
USE: 'use';
TRAIT: 'trait';

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
