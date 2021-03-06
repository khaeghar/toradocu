package org.toradocu.translator;

import edu.stanford.nlp.semgraph.SemanticGraph;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.toradocu.extractor.DocumentedMethod;
import org.toradocu.extractor.ParamTag;
import org.toradocu.util.Reflection;

/**
 * Collects all the Java elements that can be used for the condition translation. Java elements are
 * collected through Java reflection.
 */
public class JavaElementsCollector {

  /**
   * Collects all the Java code elements that can be used for the condition translation. The code
   * elements are collected using reflection starting from the given method.
   *
   * @param documentedMethod the method from which to start to collect the code elements
   * @return the collected code elements
   */
  public static Set<CodeElement<?>> collect(DocumentedMethod documentedMethod) {
    Set<CodeElement<?>> collectedElements = new LinkedHashSet<>();
    Class<?> containingClass =
        Reflection.getClass(documentedMethod.getContainingClass().getQualifiedName());

    // The containing class cannot be loaded. Return an empty set of code elements.
    if (containingClass == null) {
      return collectedElements;
    }

    List<Type> inScopeTypes = new ArrayList<>();
    inScopeTypes.add(containingClass);

    // Add the containing class as a code element.
    collectedElements.add(new ClassCodeElement(containingClass));

    // Add parameters of the documented method.
    final Executable executable = documentedMethod.getExecutable();
    int paramIndex = 0;
    List<Parameter> parameters = new ArrayList<>(Arrays.asList(executable.getParameters()));

    // The first two parameters of enum constructors are synthetic and must be removed to
    // reflect the source code.
    if (containingClass.isEnum() && documentedMethod.isConstructor()) {
      parameters.remove(0);
      parameters.remove(0);
    }

    HashMap<String, Integer> countIds = new HashMap<>();
    Set<ParameterCodeElement> params = new HashSet<>();

    for (java.lang.reflect.Parameter par : parameters) {
      String paramName = documentedMethod.getParameters().get(paramIndex).getName();
      // Extract identifiers from param comment
      Set<String> ids = extractIdsFromParams(documentedMethod, paramName);

      for (String id : ids) {
        Integer oldValue = countIds.getOrDefault(id, -1);
        countIds.put(id, ++oldValue);
      }

      ParameterCodeElement p = new ParameterCodeElement(par, paramName, paramIndex, ids);
      collectedElements.add(p);
      params.add(p);

      inScopeTypes.add(par.getType());
      paramIndex++;
    }

    // TODO Create a parameter code element directly with unique identifiers, thus removing
    // mergeIdentifiers() and related methods in ParameterCodeElement.
    // Select only valid identifiers for the parameters, i.e. the unique ones (count in map is 0)
    for (ParameterCodeElement p : params) {
      Set<String> ids = p.getOtherIdentifiers();
      for (Entry<String, Integer> countId : countIds.entrySet()) {
        String identifier = countId.getKey();
        if (ids.contains(identifier) && countId.getValue() > 0) p.removeIdentifier(identifier);
      }
      p.mergeIdentifiers();
    }

    // Add methods of the target class (all but the method corresponding to documentedMethod).
    final List<Method> methods =
        Arrays.stream(containingClass.getMethods())
            .filter(
                m ->
                    !m.toGenericString().equals(executable.toGenericString())
                        && checkCompatibility(m, inScopeTypes))
            .collect(Collectors.toList());
    for (Method method : methods) {
      if (Modifier.isStatic(method.getModifiers())) {
        collectedElements.add(new StaticMethodCodeElement(method));
      } else if (!documentedMethod.isConstructor()) {
        collectedElements.add(new MethodCodeElement("target", method));
      }
    }

    // Add fields of the target class.
    for (Field field : containingClass.getFields()) {
      collectedElements.add(new FieldCodeElement("target", field));
    }

    return collectedElements;
  }

  /**
   * For the parameter in input, find its param tag in the method's Javadoc and produce the
   * SemanticGraphs of the comment. For every graph, keep the root as identifier.
   *
   * @param method the DocumentedMethod which the parameter belongs to
   * @param param the parameter
   * @return the extracted ids
   */
  private static Set<String> extractIdsFromParams(DocumentedMethod method, String param) {
    Set<ParamTag> paramTags = method.paramTags();
    Set<String> ids = new HashSet<>();
    for (ParamTag pt : paramTags) {
      String paramName = pt.parameter().getName();
      if (paramName.equals(param)) {
        List<SemanticGraph> sgs = StanfordParser.getSemanticGraphs(pt.getComment());
        for (SemanticGraph sg : sgs) ids.add(sg.getFirstRoot().word());
      }
    }
    return ids;
  }

  private static boolean checkCompatibility(Method m, List<Type> inScopeTypes) {
    for (java.lang.reflect.Parameter parameter : m.getParameters()) {
      if (!inScopeTypes.contains(parameter.getType())) {
        return false;
      }
    }
    return true;
  }
}
