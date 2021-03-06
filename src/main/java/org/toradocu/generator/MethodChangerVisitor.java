package org.toradocu.generator;

import com.github.javaparser.ASTHelper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclaratorId;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.ModifierVisitorAdapter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.Toradocu;
import org.toradocu.conf.Configuration;
import org.toradocu.extractor.DocumentedMethod;
import org.toradocu.extractor.ParamTag;
import org.toradocu.extractor.Parameter;
import org.toradocu.extractor.ReturnTag;
import org.toradocu.extractor.ThrowsTag;
import org.toradocu.util.Checks;

/**
 * Visitor that modifies the aspect template (see method {@code visit}) to generate an aspect
 * (oracle) for a {@code DocumentedMethod}.
 */
public class MethodChangerVisitor extends ModifierVisitorAdapter<DocumentedMethod> {

  /** {@code Logger} for this class. */
  private static final Logger log = LoggerFactory.getLogger(MethodChangerVisitor.class);
  /** Holds Toradocu configuration options. */
  private final Configuration conf = Toradocu.configuration;

  /**
   * Modifies the methods {@code advice} and {@code getExpectedExceptions} of the aspect template,
   * injecting the appropriate source code to get an aspect (oracle) for the method arg.
   *
   * @param methodDeclaration the method declaration of the method to visit
   * @param documentedMethod the {@code DocumentedMethod} for which to generate the aspect (oracle)
   * @return the {@code methodDeclaration} modified as and when needed
   * @throws NullPointerException if {@code methodDeclaration} or {@code documentedMethod} is null
   */
  @Override
  public Node visit(MethodDeclaration methodDeclaration, DocumentedMethod documentedMethod) {
    Checks.nonNullParameter(methodDeclaration, "methodDeclaration");
    Checks.nonNullParameter(documentedMethod, "documentedMethod");

    switch (methodDeclaration.getName()) {
      case "advice":
        adviceChanger(methodDeclaration, documentedMethod);
        break;
      case "getExpectedExceptions":
        getExpectedExceptionChanger(methodDeclaration, documentedMethod);
        break;
      case "paramTagsSatisfied":
        paramTagSatisfiedChanger(methodDeclaration, documentedMethod);
        break;
      case "checkResult":
        checkResultChanger(methodDeclaration, documentedMethod);
        break;
    }
    return methodDeclaration;
  }

  private void checkResultChanger(
      MethodDeclaration methodDeclaration, DocumentedMethod documentedMethod) {
    ReturnTag tag = documentedMethod.returnTag();

    ReturnStmt returnResultStmt = new ReturnStmt();
    returnResultStmt.setExpr(new NameExpr("result"));

    if (tag == null || tag.getCondition().orElse("").isEmpty()) {
      methodDeclaration.getBody().getStmts().add(returnResultStmt);
      return;
    }

    // Remove whitespaces to do not influence parsing.
    String spec = tag.getCondition().get().replace(" ", "");

    String guardCondition = addCasting(spec.substring(0, spec.indexOf("?")), documentedMethod);
    String propertiesStr = spec.substring(spec.indexOf("?") + 1);
    String[] properties = propertiesStr.split(":", 2);
    String castedProperty = addCasting(properties[0], documentedMethod);
    String thenBlock = createBlock("if ((" + castedProperty + ")==false) { fail(\"Error!\"); }");

    IfStmt ifStmt;
    if (properties.length > 1) {
      String castedProperty1 = addCasting(properties[1], documentedMethod);
      String elseBlock = createBlock("if ((" + castedProperty1 + ")==false) { fail(\"Error!\"); }");
      ifStmt = createIfStmt(guardCondition, tag.getComment(), thenBlock, elseBlock);
    } else {
      ifStmt = createIfStmt(guardCondition, tag.getComment(), thenBlock);
    }
    methodDeclaration.getBody().getStmts().add(ifStmt);
    methodDeclaration.getBody().getStmts().add(returnResultStmt);
  }

  private void paramTagSatisfiedChanger(
      MethodDeclaration methodDeclaration, DocumentedMethod documentedMethod) {
    boolean returnStmtNeeded = true;
    for (ParamTag tag : documentedMethod.paramTags()) {
      String condition = tag.getCondition().orElse("");
      if (condition.isEmpty()) {
        continue;
      }
      condition = addCasting(condition, documentedMethod);
      String thenBlock = createBlock("return true;");
      String elseBlock = createBlock("return false;");
      IfStmt ifStmt = createIfStmt(condition, tag.toString(), thenBlock, elseBlock);
      methodDeclaration.getBody().getStmts().add(ifStmt);
      returnStmtNeeded = false;
    }

    if (returnStmtNeeded) {
      ReturnStmt returnTrueStmt = new ReturnStmt();
      returnTrueStmt.setExpr(new BooleanLiteralExpr(true));
      methodDeclaration.getBody().getStmts().add(returnTrueStmt);
    }
  }

  private void getExpectedExceptionChanger(
      MethodDeclaration methodDeclaration, DocumentedMethod documentedMethod) {
    for (ThrowsTag tag : documentedMethod.throwsTags()) {
      String condition = tag.getCondition().orElse("");
      if (condition.isEmpty()) {
        continue;
      }

      condition = addCasting(condition, documentedMethod);

      IfStmt ifStmt = new IfStmt();
      Expression conditionExpression;
      try {
        conditionExpression = JavaParser.parseExpression(condition);
        ifStmt.setCondition(conditionExpression);
        // Add a try-catch block to prevent runtime error when looking for an exception type
        // that is not on the classpath.
        String addExpectedException =
            "{try{expectedExceptions.add("
                + "Class.forName(\""
                + tag.exceptionType()
                + "\")"
                + ");} catch (ClassNotFoundException e) {"
                + "System.err.println(\"Class not found!\" + e);}}";
        ifStmt.setThenStmt(JavaParser.parseBlock(addExpectedException));

        // Add a try-catch block to avoid NullPointerException to be raised while evaluating a
        // boolean condition generated by Toradocu. For example, suppose that the first argument
        // of a method is null, and that Toradocu generates a condition like
        // args[0].isEmpty()==true. The condition generates a NullPointerException that we want
        // to ignore.
        ClassOrInterfaceType nullPointerException =
            new ClassOrInterfaceType("java.lang.NullPointerException");
        Position position = new Position(0, 0);
        Range range = new Range(position, position);
        CatchClause catchClause =
            new CatchClause(
                range,
                0,
                null,
                nullPointerException,
                new VariableDeclaratorId("e"),
                JavaParser.parseBlock("{}"));
        List<CatchClause> catchClauses = new ArrayList<>();
        catchClauses.add(catchClause);

        TryStmt nullCheckTryCatch = new TryStmt();
        nullCheckTryCatch.setTryBlock(JavaParser.parseBlock("{" + ifStmt.toString() + "}"));
        nullCheckTryCatch.setCatchs(catchClauses);

        // Add comment to if condition. The comment is the original comment in the Java source
        // code that has been translated by Toradocu in the commented boolean condition.
        // Comment has to be added here, cause otherwise is ignored by JavaParser.parseBlock.
        final Optional<Statement> ifCondition =
            nullCheckTryCatch
                .getTryBlock()
                .getStmts()
                .stream()
                .filter(stm -> stm instanceof IfStmt)
                .findFirst();
        if (ifCondition.isPresent()) {
          String comment = " " + tag.getKind() + " " + tag.exceptionType() + " " + tag.getComment();
          ifCondition.get().setComment(new LineComment(comment));
        }

        ASTHelper.addStmt(methodDeclaration.getBody(), nullCheckTryCatch);
      } catch (ParseException e) {
        log.error("Parsing error during the aspect creation.", e);
        e.printStackTrace();
      }
    }

    try {
      ASTHelper.addStmt(
          methodDeclaration.getBody(), JavaParser.parseStatement("return expectedExceptions;"));
    } catch (ParseException e) {
      log.error("Parsing error during the aspect creation.", e);
      e.printStackTrace();
    }
  }

  private void adviceChanger(
      MethodDeclaration methodDeclaration, DocumentedMethod documentedMethod) {
    String pointcut;

    if (documentedMethod.isConstructor()) {
      pointcut = "execution(" + getPointcut(documentedMethod) + ")";
    } else {
      pointcut = "call(" + getPointcut(documentedMethod) + ")";
      String testClassName = conf.getTestClass();
      if (testClassName != null) {
        pointcut += " && within(" + testClassName + ")";
      }
    }

    AnnotationExpr annotation =
        new SingleMemberAnnotationExpr(new NameExpr("Around"), new StringLiteralExpr(pointcut));
    List<AnnotationExpr> annotations = methodDeclaration.getAnnotations();
    annotations.add(annotation);
    methodDeclaration.setAnnotations(annotations);
  }

  private static String createBlock(String content) {
    return "{" + content + "}";
  }

  private static IfStmt createIfStmt(String condition, String comment, String thenBlock) {
    return createIfStmt(condition, comment, thenBlock, "");
  }

  private static IfStmt createIfStmt(
      String condition, String comment, String thenBlock, String elseBlock) {
    IfStmt ifStmt = new IfStmt();
    Expression conditionExpression;
    try {
      conditionExpression = JavaParser.parseExpression(condition);
      ifStmt.setCondition(conditionExpression);
      ifStmt.setThenStmt(JavaParser.parseBlock(thenBlock));
      if (!elseBlock.isEmpty()) {
        ifStmt.setElseStmt(JavaParser.parseBlock(elseBlock));
      }
      ifStmt.setComment(new LineComment(" " + comment));
    } catch (ParseException e) {
      log.error("Parsing error during the aspect creation.", e);
      e.printStackTrace();
    }
    return ifStmt;
  }

  /**
   * Generates the AspectJ pointcut definition to be used to match the given {@code
   * DocumentedMethod}. A pointcut definition looks like {@code call(void C.foo())}. Given a {@code
   * DocumentedMethod} describing the method C.foo(), this method returns the string {@code
   * call(void C.foo())}.
   *
   * @param method {@code DocumentedMethod} for which to generate the pointcut definition
   * @return the pointcut definition matching {@code method}
   */
  private static String getPointcut(DocumentedMethod method) {
    StringBuilder pointcut = new StringBuilder();

    if (method.isConstructor()) { // Constructors
      pointcut.append(method.getContainingClass()).append(".new(");
    } else { // Regular methods
      pointcut
          .append(method.getReturnType())
          .append(" ")
          .append(method.getContainingClass())
          .append(".")
          .append(method.getName())
          .append("(");
    }

    Iterator<Parameter> parametersIterator = method.getParameters().iterator();
    while (parametersIterator.hasNext()) {
      Parameter parameter = parametersIterator.next();
      pointcut.append(parameter.getType());
      if (parametersIterator.hasNext()) {
        pointcut.append(", ");
      }
    }

    pointcut.append(")");
    return pointcut.toString();
  }

  /**
   * Add the appropriate cast to each mention of a method argument or target in a given Java boolean
   * condition.
   *
   * @param condition the Java boolean condition to which add the casts
   * @param method the method to which the {@code condition} belongs
   * @return the input condition with casted method arguments and target
   * @throws NullPointerException if {@code condition} or {@code method} is null
   */
  private static String addCasting(String condition, DocumentedMethod method) {
    Checks.nonNullParameter(condition, "condition");
    Checks.nonNullParameter(method, "method");

    int index = 0;
    for (Parameter parameter : method.getParameters()) {
      String type = parameter.getType().getQualifiedName();
      condition = condition.replace("args[" + index + "]", "((" + type + ") args[" + index + "])");
      index++;
    }

    // Casting of result object in condition.
    String returnType = method.getReturnType().toString();
    if (returnType != null && !returnType.equals("void")) {
      condition = condition.replace("result", "((" + method.getReturnType() + ") result)");
    }

    // Casting of target object in condition.
    condition = condition.replace("target.", "((" + method.getContainingClass() + ") target).");
    return condition;
  }
}
