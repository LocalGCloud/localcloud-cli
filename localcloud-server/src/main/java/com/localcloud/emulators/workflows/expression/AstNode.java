package com.localcloud.emulators.workflows.expression;

import java.util.List;

public sealed interface AstNode {
    record NumberLiteral(double value) implements AstNode {}
    record StringLiteral(String value) implements AstNode {}
    record BooleanLiteral(boolean value) implements AstNode {}
    record NullLiteral() implements AstNode {}
    record Variable(String name) implements AstNode {}
    record BinaryOp(String operator, AstNode left, AstNode right) implements AstNode {}
    record UnaryOp(String operator, AstNode operand) implements AstNode {}
    record FunctionCall(String name, List<AstNode> arguments) implements AstNode {}
    record MemberAccess(AstNode object, String field) implements AstNode {}
    record IndexAccess(AstNode object, AstNode index) implements AstNode {}
    record ListLiteral(List<AstNode> elements) implements AstNode {}
    record MapLiteral(List<String> keys, List<AstNode> values) implements AstNode {}
}
