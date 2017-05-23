package org.toradocu.extractor;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.toradocu.Checks;

/**
 * ExecutableMember represents the Javadoc documentation for a method in a class. It identifies the
 * method itself and key Javadoc information associated with it, such as throws, param, and return
 * tags.
 */
public final class ExecutableMember {

  /** Reflection executable of this ExecutableMember. */
  private final Executable executable;

  private final List<Parameter> parameters;

  private final List<Tag> tags;

  // Tags caches.
  private final List<ParamTag> paramTags;
  private final List<ThrowsTag> throwsTags;
  private ReturnTag returnTag;

  public ExecutableMember(Executable executable) throws ClassNotFoundException {
    this(executable, new ArrayList<>(), new ArrayList<>());
  }

  public ExecutableMember(Executable executable, List<Tag> tags) throws ClassNotFoundException {
    this(executable, new ArrayList<>(), tags);
  }

  public ExecutableMember(Executable executable, List<Parameter> parameters, List<Tag> tags)
      throws ClassNotFoundException {
    Checks.nonNullParameter(executable, "executable");
    Checks.nonNullParameter(parameters, "parameters");
    Checks.nonNullParameter(tags, "tags");

    this.executable = executable;
    checkParameters(executable.getParameters(), parameters);
    this.parameters = parameters;
    // TODO Checks that param tag names are actually consistent with parameters names. Javadoc may contain errors.
    this.tags = tags;

    // Load tags caches.
    paramTags = new ArrayList<>();
    throwsTags = new ArrayList<>();
    returnTag = null;
    loadTags(tags);
  }

  // Checks that provided parameter types are consistent with executable (reflection) parameter types.
  private void checkParameters(
      java.lang.reflect.Parameter[] executableParams, List<Parameter> params) {
    if (executableParams.length != params.size()) {
      throw new IllegalArgumentException(
          "Expected "
              + executableParams.length
              + " parameters, "
              + "but "
              + params.size()
              + " provided.");
    }

    int i = 0;
    for (Parameter p : params) {
      final java.lang.reflect.Parameter execParam = executableParams[i++];
      if (!execParam.getType().equals(p.getType())) {
        throw new IllegalArgumentException(
            "Expected parameter types are "
                + Arrays.toString(executableParams)
                + " while provided types are "
                + params);
      }
    }
  }

  private void loadTags(List<Tag> tags) {
    for (Tag tag : tags) {
      if (tag instanceof ParamTag) {
        paramTags.add((ParamTag) tag);
      } else if (tag instanceof ReturnTag) {
        if (returnTag == null) {
          returnTag = (ReturnTag) tag;
        } else {
          throw new IllegalArgumentException(
              "Javadoc documentation must contain only one @return tag");
        }
      } else if (tag instanceof ThrowsTag) {
        throwsTags.add((ThrowsTag) tag);
      }
    }
  }

  /**
   * Returns an unmodifiable view of the throws tags in this method.
   *
   * @return an unmodifiable view of the throws tags in this method
   */
  public List<ThrowsTag> throwsTags() {
    return Collections.unmodifiableList(throwsTags);
  }

  /**
   * Returns an unmodifiable view of the param tags in this method.
   *
   * @return an unmodifiable view of the param tags in this method.
   */
  public List<ParamTag> paramTags() {
    return Collections.unmodifiableList(paramTags);
  }

  /**
   * Returns the return tag in this method.
   *
   * @return the return tag in this method. Null if there is no @return comment
   */
  public ReturnTag returnTag() {
    return returnTag;
  }

  /**
   * Returns true if this method takes a variable number of arguments, false otherwise.
   *
   * @return {@code true} if this method takes a variable number of arguments, {@code false}
   *     otherwise
   */
  public boolean isVarArgs() {
    return executable.isVarArgs();
  }

  /**
   * Returns the simple name of this method.
   *
   * @return the simple name of this method
   */
  public String getName() {
    return executable.getName();
  }

  /**
   * Returns true if this method is a constructor, false otherwise.
   *
   * @return {@code true} if this method is a constructor, {@code false} otherwise
   */
  public boolean isConstructor() {
    return executable instanceof Constructor;
  }

  /**
   * Returns an unmodifiable list view of the parameters in this method.
   *
   * @return an unmodifiable list view of the parameters in this method
   */
  public List<Parameter> getParameters() {
    return Collections.unmodifiableList(parameters);
  }

  /**
   * Returns the signature of this method.
   *
   * @return the signature of this method
   */
  public String getSignature() {
    StringJoiner joiner = new StringJoiner(",", "(", ")");
    for (Parameter param : parameters) {
      joiner.add(param.toString());
    }
    return executable.getName() + joiner.toString();
  }

  /**
   * Returns the return type of this method or null if this is a constructor.
   *
   * @return the return type of this method or {@code null} if this is a constructor
   */
  public AnnotatedType getReturnType() {
    return executable.getAnnotatedReturnType();
  }

  /**
   * Returns the fully qualified class name in which this executable member is defined.
   *
   * @return the class in which this executable member is defined
   */
  public String getContainingClass() {
    return executable.getDeclaringClass().getName();
  }

  /**
   * Returns the executable member of this constructor/method.
   *
   * @return the executable member of this constructor/method
   */
  public Executable getExecutable() {
    return executable;
  }

  /**
   * Returns true if this {@code ExecutableMember} and the specified object are equal.
   *
   * @param obj the object to test for equality
   * @return true if this object and {@code obj} are equal
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ExecutableMember)) {
      return false;
    }

    ExecutableMember that = (ExecutableMember) obj;
    return this.executable.equals(that.executable)
        && this.parameters.equals(that.parameters)
        && this.tags.equals(that.tags);
  }

  /**
   * Returns the hash code of this object.
   *
   * @return the hash code of this object
   */
  @Override
  public int hashCode() {
    return Objects.hash(executable, parameters, tags);
  }

  /**
   * Returns the string representation of this executable member as returned by {@code
   * java.lang.reflect.Executable#toString}
   *
   * @return return the string representation of this method
   */
  @Override
  public String toString() {
    return executable.toString();
  }
}