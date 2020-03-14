package com.acxiom.pipeline.utils

import java.lang.reflect.InvocationTargetException

import com.acxiom.pipeline._
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.reflect.runtime.universe._
import scala.reflect.runtime.{universe => ru}
import scala.runtime.BoxedUnit
import scala.util.{Failure, Success, Try}

object ReflectionUtils {
  private val logger = Logger.getLogger(getClass)

  /**
   * This function will attempt to find and instantiate the named class with the given parameters.
   *
   * @param className              The fully qualified class name
   * @param parameters             The parameters to pass to the constructor or None
   * @param validateParameterTypes Enable method paramter type validation.
   * @return An instantiated class.
   */
  def loadClass(className: String, parameters: Option[Map[String, Any]] = None, validateParameterTypes: Boolean = false): Any = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val moduleClass = mirror.staticClass(className)
    val module = mirror.staticModule(className)
    val classMirror = mirror.reflectClass(moduleClass)
    // Get constructor
    val symbols = classMirror.symbol.info.decls.filter(s => s.isConstructor).toList
    if (symbols.nonEmpty) {
      val method = getMethodBySymbol(symbols.head, parameters.getOrElse(Map[String, Any]()), Some(symbols))
      classMirror.reflectConstructor(method)
      classMirror.reflectConstructor(method)(mapMethodParameters(method.paramLists.head, parameters.getOrElse(Map[String, Any]()), mirror,
        mirror.reflect(mirror.reflectModule(module)), symbols.head.asTerm.fullName, method.typeSignature, None, None, None, validateParameterTypes)
        : _*)
    }
  }

  /**
   * This function will execute the PipelineStep function using the provided parameter values.
   *
   * @param step            The step to execute
   * @param pipeline        The pipeline to process the step in
   * @param parameterValues A map of named parameter values to map to the step function parameters.
   * @return The result of the step function execution.
   */
  def processStep(step: PipelineStep,
                  pipeline: Pipeline,
                  parameterValues: Map[String, Any],
                  pipelineContext: PipelineContext): Any = {
    logger.debug(s"processing step,stepObject=$step")
    // Get the step directive which should follow the pattern "Object.function"
    val executionObject = step.engineMeta.get.spark.get
    // Get the object and function
    val directives = executionObject.split('.')
    val objName = directives(0)
    val funcName = directives(1)
    logger.info(s"Preparing to run step $objName.$funcName")
    // Get the reflection information for the object and method
    val mirror = ru.runtimeMirror(getClass.getClassLoader)

    val stepPackage = getStepPackage(objName, mirror, step.engineMeta.get.pkg, pipelineContext)
    val module = mirror.staticModule(s"${stepPackage.getOrElse("")}.$objName")
    val im = mirror.reflectModule(module)
    val method = getMethod(funcName, im, parameterValues)
    // Get a handle to the actual object
    val stepObject = mirror.reflect(im.instance)
    // Get the list of methods generated by the compiler
    val ts = stepObject.symbol.typeSignature
    // Get the parameters this method requires
    val parameters = method.paramLists.head
    val validateTypes = pipelineContext.getGlobal("validateStepParameterTypes").getOrElse(false).asInstanceOf[Boolean]
    val params = mapMethodParameters(parameters, parameterValues, mirror, stepObject, funcName, ts, Some(pipelineContext), step.id, pipeline.id, validateTypes)
    logger.info(s"Executing step $objName.$funcName")
    logger.debug(s"Parameters: $params")
    // Invoke the method
    try {
      stepObject.reflectMethod(method)(params: _*) match {
        // a pipeline step response is returned as is
        case response: PipelineStepResponse => response
        // all others are wrapped in a pipeline step response when empty secondary named parameters
        case response => PipelineStepResponse(response match {
          case value: Option[_] => value
          case _: BoxedUnit => None
          case _ => Some(response)
        }, None)
      }
    } catch {
      case it: InvocationTargetException => throw it.getTargetException
      case t: Throwable => throw t
    }
  }

  /**
   * execute a function on an existing object by providing the function name and parameters (in expected order)
   *
   * @param obj      the object with the function that needs to be run
   * @param funcName the name of the function on the object to run
   * @param params   the parameters required for the function (in proper order)
   * @return the results of the executed function
   */
  def executeFunctionByName(obj: Any, funcName: String, params: List[Any]): Any = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val reflectedObj = mirror.reflect(obj)
    val ts = reflectedObj.symbol.typeSignature
    val method = ts.member(TermName(funcName)).asMethod
    reflectedObj.reflectMethod(method)(params: _*)
  }

  /**
   * This function will attempt to extract the value assigned to the field name from the given entity. The field can
   * contain "." character to denote sub objects. If the field does not exist or is empty a None object will be
   * returned. This function does not handle collections.
   *
   * @param entity            The entity containing the value.
   * @param fieldName         The name of the field to extract
   * @param extractFromOption Setting this to true will see if the value is an option and extract the sub value.
   * @return The value from the field or an empty string
   */
  def extractField(entity: Any, fieldName: String, extractFromOption: Boolean = true): Any = {
    if (fieldName == "") {
      entity
    } else {
      val value = getField(entity, fieldName)
      if (extractFromOption) {
        value match {
          case option: Option[_] if option.isDefined => option.get
          case _ => value
        }
      } else {
        value
      }
    }
  }

  @tailrec
  private def getField(entity: Any, fieldName: String): Any = {
    val obj = entity match {
      case option: Option[_] if option.isDefined =>
        option.get
      case _ => if (!entity.isInstanceOf[Option[_]]) {
        entity
      } else {
        ""
      }
    }
    val embedded = fieldName.contains(".")
    val name = if (embedded) {
      fieldName.substring(0, fieldName.indexOf("."))
    } else {
      fieldName
    }

    val value = obj match {
      case map: Map[_, _] => map.asInstanceOf[Map[String, Any]].getOrElse(name, None)
      case _ => getFieldValue(obj, name)
    }

    if (value != None && embedded) {
      getField(value, fieldName.substring(fieldName.indexOf(".") + 1))
    } else {
      value
    }
  }

  private def getFieldValue(obj: Any, fieldName: String): Any = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val im = mirror.reflect(obj)
    val term = im.symbol.typeSignature.member(ru.TermName(fieldName))
    if (term.isTerm) {
      val field = term.asTerm
      val fieldVal = im.reflectField(field)
      fieldVal.get
    } else {
      None
    }
  }

  private def mapMethodParameters(parameters: List[ru.Symbol], parameterValues: Map[String, Any], runtimeMirror: Mirror,
                                  stepObject: ru.InstanceMirror, funcName: String, ts: ru.Type,
                                  pipelineContext: Option[PipelineContext], stepId: Option[String],
                                  pipelineId: Option[String],
                                  validateParameterTypes: Boolean) = {
    parameters.zipWithIndex.map { case (param, pos) =>
      val name = param.name.toString
      logger.debug(s"Mapping parameter $name")
      val optionType = param.asTerm.typeSignature.toString.startsWith("Option[") ||
        param.asTerm.typeSignature.toString.startsWith("scala.Option[")
      val value = if (parameterValues.contains(name)) {
        parameterValues(name)
      } else if (param.asTerm.isParamWithDefault) {
        logger.debug("Mapping parameter from function default parameter value")
        val term = ts.member(ru.TermName(s"$funcName$$default$$${pos + 1}"))
        if (term != NoSymbol) {
          // Locate the generated method that will provide the default value for this parameter
          // Name follows Scala spec --> {functionName}$$default$${parameterPosition} + 1
          val defaultGetterMethod = term.asMethod
          // Execute the method to get the default value for this parameter
          stepObject.reflectMethod(defaultGetterMethod)()
        } else {
          // This is probably a constructor default parameter, so it needs to be invoked differently
          Class.forName(funcName.replaceAll("\\.<init>", ""))
            .getMethod(s"$$lessinit$$greater$$default$$${pos + 1}").invoke(null)
        }
      } else {
        logger.debug("Using built in pipeline variable")
        getBuiltInParameter(pipelineContext, name)
      }

      val finalValue = if (pipelineContext.isDefined) {
        pipelineContext.get.security.secureParameter(getFinalValue(param.asTerm.typeSignature, value))
      } else {
        getFinalValue(param.asTerm.typeSignature, value)
      }

      val finalValueType = finalValue match {
        case v: Option[_] =>
          if (v.asInstanceOf[Option[_]].isEmpty) "None" else s"Some(${v.asInstanceOf[Option[_]].get.getClass.getSimpleName})"
        case _ => finalValue.getClass.getSimpleName
      }
      if (validateParameterTypes) {
        validateParamTypeAssignment(runtimeMirror, param, optionType, finalValue, finalValueType, funcName, stepId, pipelineId)
      }
      logger.debug(s"Mapping parameter to method $funcName,paramName=$name,paramType=${param.typeSignature}," +
        s"valueType=$finalValueType,value=$finalValue")
      finalValue
    }
  }

  private def getBuiltInParameter(pipelineContext: Option[PipelineContext], name: String) = {
    name match {
      case "pipelineContext" => if (pipelineContext.isDefined) pipelineContext.get
      case _ => None
    }
  }

  private def validateParamTypeAssignment(runtimeMirror: Mirror,
                                          param: Symbol,
                                          isOption: Boolean,
                                          value: Any,
                                          valueType: String,
                                          funcName: String,
                                          stepId: Option[String],
                                          pipelineId: Option[String]): Unit = {
    val paramType = if (isOption) param.typeSignature.typeArgs.head else param.typeSignature
    if (!(isOption && value.asInstanceOf[Option[_]].isEmpty) && !(paramType =:= typeOf[Any])) {
      val finalValue = if (isOption) value.asInstanceOf[Option[_]].get else value
      val paramClass = runtimeMirror.runtimeClass(paramType)
      val isAssignable = isAssignableFrom(paramClass, finalValue)
      if (!isAssignable) {
        val stepMessage = if (stepId.isDefined) s"in step [${stepId.get}]" else ""
        val pipelineMessage = if (pipelineId.isDefined) s"in pipeline [${pipelineId.get}]" else ""
        val message = s"Failed to map value [$value] of type [$valueType] to paramName [${param.name.toString}] of" +
          s" type [${param.typeSignature}] for method [$funcName] $stepMessage $pipelineMessage"
        throw PipelineException(message = Some(message), stepId = stepId, pipelineId = pipelineId)
      }
    }
  }

  private def isAssignableFrom(paramClass: Class[_], value: Any): Boolean = {
    value match {
      case b: java.lang.Boolean => paramClass.isAssignableFrom(b.asInstanceOf[Boolean].getClass)
      case i: java.lang.Integer => paramClass.isAssignableFrom(i.asInstanceOf[Int].getClass)
      case l: java.lang.Long => paramClass.isAssignableFrom(l.asInstanceOf[Long].getClass)
      case s: java.lang.Short => paramClass.isAssignableFrom(s.asInstanceOf[Short].getClass)
      case c: java.lang.Character => paramClass.isAssignableFrom(c.asInstanceOf[Char].getClass)
      case d: java.lang.Double => paramClass.isAssignableFrom(d.asInstanceOf[Double].getClass)
      case f: java.lang.Float => paramClass.isAssignableFrom(f.asInstanceOf[Float].getClass)
      case by: java.lang.Byte => paramClass.isAssignableFrom(by.asInstanceOf[Byte].getClass)
      case _ => paramClass.isAssignableFrom(value.getClass)
    }
  }

  private def getFinalValue(paramType: ru.Type, value: Any): Any = {
    val optionType = paramType.toString.startsWith("Option[") || paramType.toString.startsWith("scala.Option[")
    if (optionType && !value.isInstanceOf[Option[_]]) {
      Some(wrapValueInCollection(paramType, optionType, value))
    } else if (!optionType && value.isInstanceOf[Option[_]]) {
      if (value.asInstanceOf[Option[_]].isDefined) {
        wrapValueInCollection(paramType, optionType, value.asInstanceOf[Option[_]].get)
      } else {
        value
      }
    } else {
      wrapValueInCollection(paramType, optionType, value)
    }
  }

  private def wrapValueInCollection(paramType: ru.Type, isOption: Boolean, value: Any): Any = {
    val collectionType = isCollection(isOption, paramType)
    val typeName = if (isOption) paramType.typeArgs.head.toString else paramType.toString
    if (collectionType) {
      typeName match {
        case t if t.startsWith("List[") && !value.isInstanceOf[List[_]] =>
          List(value)
        case t if t.startsWith("Seq[") && !value.isInstanceOf[Seq[_]] =>
          Seq(value)
        case _ => value
      }
    } else {
      value
    }
  }

  private def getMethod(funcName: String, im: ru.ModuleMirror, parameterValues: Map[String, Any]): ru.MethodSymbol = {
    val symbol = im.symbol.info.decl(ru.TermName(funcName))
    if (!symbol.isTerm) {
      throw new IllegalArgumentException(s"$funcName is not a valid function!")
    }
    getMethodBySymbol(symbol, parameterValues)
  }

  private def getMethodBySymbol(symbol: ru.Symbol, parameterValues: Map[String, Any], alternativeList: Option[List[ru.Symbol]] = None): ru.MethodSymbol = {
    val alternatives = if (alternativeList.isDefined) {
      alternativeList.get
    } else {
      symbol.asTerm.alternatives
    }

    // See if more than one method has the same name and use the parameters to determine which to use
    if (alternatives.lengthCompare(1) > 0) {
      // Iterate through the functions matching the number of parameters that have the same typed as the provided parameters
      val method = alternatives.reduce((alt1, alt2) => {
        val params1 = getMatches(alt1.asMethod.paramLists.head, parameterValues)
        val params2 = getMatches(alt2.asMethod.paramLists.head, parameterValues)
        if (params1 == params2 &&
          (parameterValues.size - alt1.asMethod.paramLists.head.length >
            parameterValues.size - alt2.asMethod.paramLists.head.length)) {
          alt1
        } else if (params1 > params2) {
          alt1
        } else {
          alt2
        }
      })

      method.asMethod
    } else {
      // There was only one method matching the name so return it.
      symbol.asMethod
    }
  }

  private def getMatches(symbols: List[ru.Symbol], parameterValues: Map[String, Any]): Int = {
    // Filter out the parameters returning only parameters that are compatible
    val matches = symbols.filter(param => {
      val name = param.name.toString
      if (parameterValues.contains(name)) {
        val paramType = Class.forName(param.typeSignature.typeSymbol.fullName)
        val instanceClass = getFinalValue(param.asTerm.typeSignature, parameterValues(name)).getClass
        val instanceType = if (instanceClass.getName == "java.lang.Boolean") Class.forName("scala.Boolean") else instanceClass
        parameterValues.contains(name) && paramType.isAssignableFrom(instanceType)
      } else {
        false
      }
    })

    matches.length
  }

  private def isCollection(optionType: Boolean, paramType: ru.Type): Boolean = if (optionType) {
    paramType.typeArgs.head <:< typeOf[Seq[_]]
  } else {
    paramType <:< typeOf[Seq[_]]
  }

  private def getStepPackage(objName: String, mirror: Mirror, pkg: Option[String], pipelineContext: PipelineContext): Option[String] = {
    val stepPackages = pipelineContext.stepPackages.getOrElse(List())
    if (pkg.isDefined && pkg.get.nonEmpty) {
      pkg
    } else {
      stepPackages.find(pkg => {
        Try(mirror.staticModule(s"$pkg.$objName")) match {
          case Success(_) => true
          case Failure(_) => false
        }
      })
    }
  }
}
