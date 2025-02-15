/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cfa.parser.eclipse.c;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.core.dom.ast.IASTCompoundStatement;
import org.eclipse.cdt.core.dom.ast.IASTDeclaration;
import org.eclipse.cdt.core.dom.ast.IASTFunctionDefinition;
import org.eclipse.cdt.core.dom.ast.IASTPreprocessorIncludeStatement;
import org.eclipse.cdt.core.dom.ast.IASTProblem;
import org.eclipse.cdt.core.dom.ast.IASTStatement;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.gnu.c.GCCLanguage;
import org.eclipse.cdt.core.dom.parser.c.ANSICParserExtensionConfiguration;
import org.eclipse.cdt.core.dom.parser.c.ICParserExtensionConfiguration;
import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.parser.FileContent;
import org.eclipse.cdt.core.parser.IParserLogService;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.core.parser.ParserFactory;
import org.eclipse.cdt.internal.core.parser.IMacroDictionary;
import org.eclipse.cdt.internal.core.parser.InternalParserUtil;
import org.eclipse.cdt.internal.core.parser.scanner.InternalFileContent;
import org.eclipse.cdt.internal.core.parser.scanner.InternalFileContentProvider;
import org.eclipse.core.runtime.CoreException;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.io.Path;
import org.sosy_lab.common.io.Paths;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LogManagerWithoutDuplicates;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.CSourceOriginMapping;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.ast.c.CAstNode;
import org.sosy_lab.cpachecker.cfa.parser.Scope;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.exceptions.CParserException;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

/**
 * Wrapper for Eclipse CDT 7.0 and 8.* (internal version number since 5.2.*)
 */
class EclipseCParser implements CParser {

  protected final ILanguage language;

  protected final IParserLogService parserLog = ParserFactory.createDefaultLogService();

  private final MachineModel machine;

  private final LogManager logger;
  private final Configuration config;

  private final Timer parseTimer = new Timer();
  private final Timer cfaTimer = new Timer();

  public EclipseCParser(Configuration pConfig, LogManager pLogger,
      Dialect dialect, MachineModel pMachine) {

    this.logger = pLogger;
    this.machine = pMachine;
    this.config = pConfig;

    switch (dialect) {
    case C99:
      language = new CLanguage(new ANSICParserExtensionConfiguration());
      break;
    case GNUC:
      language = GCCLanguage.getDefault();
      break;
    default:
      throw new IllegalArgumentException("Unknown C dialect");
    }
  }

  /**
   * Convert paths like "file.c" to "./file.c",
   * and return all other patchs unchanged.
   * The pre-processor of Eclipse CDT needs this to resolve relative includes.
   */
  private static String fixPath(String pPath) {
    Path path = Paths.get(pPath);
    if (!path.isEmpty() && !path.isAbsolute() && path.getParent().isEmpty()) {
      return Paths.get(".").resolve(path).toString();
    }
    return pPath;
  }

  private FileContent wrapCode(FileContentToParse pContent) {
    return FileContent.create(fixPath(pContent.getFileName()), pContent.getFileContent().toCharArray());
  }

  private FileContent wrapCode(String pFileName, String pCode) {
    return FileContent.create(fixPath(pFileName), pCode.toCharArray());
  }

  private FileContent wrapFile(String pFileName) throws IOException {
    String code = Paths.get(pFileName).asCharSource(Charset.defaultCharset()).read();
    return wrapCode(fixPath(pFileName), code);
  }

  @Override
  public ParseResult parseFile(List<FileToParse> pFilenames, CSourceOriginMapping sourceOriginMapping) throws CParserException, IOException, InvalidConfigurationException {

    List<IASTTranslationUnit> astUnits = new ArrayList<>();
    for (FileToParse f: pFilenames) {
      astUnits.add(parse(wrapFile(f.getFileName())));
    }
    return buildCFA(astUnits, sourceOriginMapping);
  }

  @Override
  public ParseResult parseString(List<FileContentToParse> codeFragments, CSourceOriginMapping sourceOriginMapping) throws CParserException, InvalidConfigurationException {

    List<IASTTranslationUnit> astUnits = new ArrayList<>();
    for (FileContentToParse f : codeFragments) {
      astUnits.add(parse(wrapCode(f)));
    }
    return buildCFA(astUnits, sourceOriginMapping);
  }

  /**
   * This method parses a single file where no prefix for static variables is needed.
   */
  @Override
  public ParseResult parseFile(String pFilename, CSourceOriginMapping sourceOriginMapping) throws CParserException, IOException, InvalidConfigurationException {

    IASTTranslationUnit unit = parse(wrapFile(pFilename));
    List<IASTTranslationUnit> returnParam = new ArrayList<>();
    returnParam.add(unit);
    return buildCFA(returnParam, sourceOriginMapping);
  }

  /**
   * This method parses a single string, where no prefix for static variables is needed.
   */
  @Override
  public ParseResult parseString(String pFilename, String pCode, CSourceOriginMapping sourceOriginMapping) throws CParserException, InvalidConfigurationException {

    IASTTranslationUnit unit = parse(wrapCode(pFilename, pCode));
    List<IASTTranslationUnit> returnParam = new ArrayList<>();
    returnParam.add(unit);
    return buildCFA(returnParam, sourceOriginMapping);
  }

  @Override
  public CAstNode parseSingleStatement(String pCode, Scope scope) throws CParserException, InvalidConfigurationException {
    // parse
    IASTTranslationUnit ast = parse(wrapCode("", pCode));

    // strip wrapping function header
    IASTDeclaration[] declarations = ast.getDeclarations();
    if (   declarations == null
        || declarations.length != 1
        || !(declarations[0] instanceof IASTFunctionDefinition)) {
      throw new CParserException("Not a single function: " + ast.getRawSignature());
    }

    IASTFunctionDefinition func = (IASTFunctionDefinition)declarations[0];
    IASTStatement body = func.getBody();
    if (!(body instanceof IASTCompoundStatement)) {
      throw new CParserException("Function has an unexpected " + body.getClass().getSimpleName() + " as body: " + func.getRawSignature());
    }

    IASTStatement[] statements = ((IASTCompoundStatement)body).getStatements();
    if (!(statements.length == 2 && statements[1] == null || statements.length == 1)) {
      throw new CParserException("Not exactly one statement in function body: " + body);
    }

    Sideassignments sa = new Sideassignments();
    sa.enterBlock();
    return new ASTConverter(config, scope, new LogManagerWithoutDuplicates(logger), Functions.<String>identity(), new CSourceOriginMapping(), machine, "", sa)
        .convert(statements[0]);
  }

  @Override
  public List<CAstNode> parseStatements(String pCode, Scope scope) throws CParserException, InvalidConfigurationException {
    // parse
    IASTTranslationUnit ast = parse(wrapCode("", pCode));

    // strip wrapping function header
    IASTDeclaration[] declarations = ast.getDeclarations();
    if (   declarations == null
        || declarations.length != 1
        || !(declarations[0] instanceof IASTFunctionDefinition)) {
      throw new CParserException("Not a single function: " + ast.getRawSignature());
    }

    IASTFunctionDefinition func = (IASTFunctionDefinition)declarations[0];
    IASTStatement body = func.getBody();
    if (!(body instanceof IASTCompoundStatement)) {
      throw new CParserException("Function has an unexpected " + body.getClass().getSimpleName() + " as body: " + func.getRawSignature());
    }

    IASTStatement[] statements = ((IASTCompoundStatement)body).getStatements();
    if (statements.length == 1 && statements[0] == null || statements.length == 0) {
      throw new CParserException("No statement found in function body: " + body);
    }

    Sideassignments sa = new Sideassignments();
    sa.enterBlock();

    ASTConverter converter = new ASTConverter(config, scope, new LogManagerWithoutDuplicates(logger),
        Functions.<String>identity(), new CSourceOriginMapping(), machine, "", sa);

    List<CAstNode> nodeList = new ArrayList<>(statements.length);

    for (IASTStatement statement : statements) {
      if (statement != null) {
        nodeList.add(converter.convert(statement));
      }
    }

    if (nodeList.size() < 1) {
      throw new CParserException("No statement found in function body: " + body);
    }

    return nodeList;
  }

  protected static final int PARSER_OPTIONS =
            ILanguage.OPTION_IS_SOURCE_UNIT     // our code files are always source files, not header files
          | ILanguage.OPTION_NO_IMAGE_LOCATIONS // we don't use IASTName#getImageLocation(), so the parse doesn't need to create them
          ;

  private IASTTranslationUnit parse(FileContent codeReader) throws CParserException {
    parseTimer.start();
    try {
      IASTTranslationUnit result = getASTTranslationUnit(codeReader);

      // Separate handling of include problems
      // so that we can give a better error message.
      for (IASTPreprocessorIncludeStatement include : result.getIncludeDirectives()) {
        if (!include.isResolved()) {
          if (include.isSystemInclude()) {
            throw new CFAGenerationRuntimeException("File includes system headers, either preprocess it manually or specify -preprocess.");
          } else {
            throw new CFAGenerationRuntimeException("Included file " + include.getName() + " is missing", include, Functions.<String>identity());
          }
        }
      }

      // Report the preprocessor problems.
      // TODO this shows only the first problem
      for (IASTProblem problem : result.getPreprocessorProblems()) {
        throw new CFAGenerationRuntimeException(problem, Functions.<String>identity());
      }

      return result;

    } catch (CFAGenerationRuntimeException e) {
      // thrown by StubCodeReaderFactory
      throw new CParserException(e);
    } catch (CoreException e) {
      throw new CParserException(e);
    } finally {
      parseTimer.stop();
    }
  }

  private IASTTranslationUnit getASTTranslationUnit(FileContent pCode) throws CFAGenerationRuntimeException, CoreException {
    return language.getASTTranslationUnit(pCode,
                                          StubScannerInfo.instance,
                                          FileContentProvider.instance,
                                          null,
                                          PARSER_OPTIONS,
                                          parserLog);
  }

  /**
   * Builds the cfa out of a list of pairs of translation units and their appropriate prefixes for static variables
   *
   * @param asts a List of Pairs of translation units and the appropriate prefix for static variables
   * @return
   * @throws CParserException
   * @throws InvalidConfigurationException
   */
  private ParseResult buildCFA(List<IASTTranslationUnit> asts,
      CSourceOriginMapping sourceOriginMapping) throws CParserException, InvalidConfigurationException {
    checkArgument(!asts.isEmpty());
    cfaTimer.start();

    Function<String, String> niceFileNameFunction = createNiceFileNameFunction(asts);
    try {
      CFABuilder builder = new CFABuilder(config, logger, niceFileNameFunction, sourceOriginMapping, machine);

      // we don't need any file prefix if we only have one file
      if (asts.size() == 1) {
        builder.analyzeTranslationUnit(asts.get(0), "");

        // in case of several files we need to add a file prefix to global variables
        // as there could be several equally named files in different directories
        // we consider not only the file name but also the path for creating
        // the prefix
      } else {
        for (IASTTranslationUnit ast : asts) {
          builder.analyzeTranslationUnit(ast, niceFileNameFunction.apply(ast.getFilePath()).replace("/", "_").replaceAll("\\W", "_"));
        }
      }

      return builder.createCFA();

    } catch (CFAGenerationRuntimeException e) {
      throw new CParserException(e);
    } finally {
      cfaTimer.stop();
    }
  }

  /**
   * Given a file name, this function returns a "nice" representation of it.
   * This should be used for situations where the name is going
   * to be presented to the user.
   * The result may be the empty string, if for example CPAchecker only uses
   * one file (we expect the user to know its name in this case).
   */
  private Function<String, String> createNiceFileNameFunction(List<IASTTranslationUnit> asts) {
    Iterator<String> fileNames = Lists.transform(asts, new Function<IASTTranslationUnit, String>() {
      @Override
      public String apply(IASTTranslationUnit pInput) {
        return pInput.getFilePath();
      }}).iterator();

    if (asts.size() == 1) {
      final String mainFileName = fileNames.next();
      return new Function<String, String>() {
        @Override
        public String apply(String pInput) {
          return mainFileName.equals(pInput)
              ? "" // no file name necessary for main file if there is only one
              : pInput;
          }
        };

    } else {
      String commonStringPrefix = fileNames.next();
      while (fileNames.hasNext()) {
        commonStringPrefix = Strings.commonPrefix(commonStringPrefix, fileNames.next());
      }

      final String commonPathPrefix;
      int pos = commonStringPrefix.lastIndexOf(File.separator);
      if (pos < 0) {
        commonPathPrefix = commonStringPrefix;
      } else {
        commonPathPrefix = commonStringPrefix.substring(0, pos+1);
      }

      return new Function<String, String>() {
          @Override
          public String apply(String pInput) {
            if (pInput.isEmpty()) {
              return pInput;
            }
            if (pInput.charAt(0) == '"' && pInput.charAt(pInput.length()-1) == '"') {
              pInput = pInput.substring(1, pInput.length()-1);
            }
            if (pInput.startsWith(commonPathPrefix)) {
              return pInput.substring(commonPathPrefix.length()).intern();
            } else {
              return pInput.intern();
            }
          }
        };
    }
  }


  @Override
  public Timer getParseTime() {
    return parseTimer;
  }

  @Override
  public Timer getCFAConstructionTime() {
    return cfaTimer;
  }


  /**
   * Private class extending the Eclipse CDT class that is the starting point
   * for using the parser.
   * Supports choise of parser dialect.
   */
  private static class CLanguage extends GCCLanguage {

    private final ICParserExtensionConfiguration parserConfig;

    public CLanguage(ICParserExtensionConfiguration parserConfig) {
      this.parserConfig = parserConfig;
    }

    @Override
    protected ICParserExtensionConfiguration getParserExtensionConfiguration() {
      return parserConfig;
    }
  }

  /**
   * Private class that tells the Eclipse CDT scanner that no macros and include
   * paths have been defined externally.
   */
  protected static class StubScannerInfo implements IScannerInfo {

    private static final ImmutableMap<String, String> MACROS;

    static {
      ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();

      // _Static_assert(cond, msg) feature of C11
      macrosBuilder.put("_Static_assert(c, m)", "");

      // These built-ins are defined as macros
      // in org.eclipse.cdt.core.dom.parser.GNUScannerExtensionConfiguration.
      // When the parser encounters their redefinition or
      // some non-trivial usage in the code, we get parsing errors.
      // So we redefine these macros to themselves in order to
      // parse them as functions.
      macrosBuilder.put("__builtin_constant_p", "__builtin_constant_p");
      macrosBuilder.put("__builtin_types_compatible_p(t1,t2)", "__builtin_types_compatible_p(({t1 arg1; arg1;}), ({t2 arg2; arg2;}))");
      macrosBuilder.put("__offsetof__", "__offsetof__");

      macrosBuilder.put("__func__", "\"__func__\"");
      macrosBuilder.put("__FUNCTION__", "\"__FUNCTION__\"");
      macrosBuilder.put("__PRETTY_FUNCTION__", "\"__PRETTY_FUNCTION__\"");

      // Eclipse CDT 8.1.1 has problems with more complex attributes
      macrosBuilder.put("__attribute__(a)", "");

      // There are some interesting macros available at
      // http://research.microsoft.com/en-us/um/redmond/projects/invisible/include/stdarg.h.htm
      macrosBuilder.put("_INTSIZEOF(n)", "((sizeof(n) + sizeof(int) - 1) & ~(sizeof(int) - 1))"); // at least size of smallest addressable unit
      //macrosBuilder.put("__builtin_va_start(ap,v)", "(ap = (va_list)&v + _INTSIZEOF(v))");
      macrosBuilder.put("__builtin_va_arg(ap,t)", "*(t *)((ap += _INTSIZEOF(t)) - _INTSIZEOF(t))");
      // macrosBuilder.put("__builtin_va_end(ap)", "(ap = (va_list)0)");

      MACROS = macrosBuilder.build();
    }

    protected final static IScannerInfo instance = new StubScannerInfo();

    @Override
    public Map<String, String> getDefinedSymbols() {
      // the externally defined pre-processor macros
      return MACROS;
    }

    @Override
    public String[] getIncludePaths() {
      return new String[0];
    }
  }

  private static class FileContentProvider extends InternalFileContentProvider {

    static final InternalFileContentProvider instance = new FileContentProvider();

    @Override
    public InternalFileContent getContentForInclusion(String pFilePath,
        IMacroDictionary pMacroDictionary) {
      return InternalParserUtil.createExternalFileContent(pFilePath,
          InternalParserUtil.SYSTEM_DEFAULT_ENCODING);
    }

    @Override
    public InternalFileContent getContentForInclusion(IIndexFileLocation pIfl,
        String pAstPath) {
      return InternalParserUtil.createFileContent(pIfl);
    }
  }
}