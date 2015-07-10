/*******************************************************************************
 * Copyright 2015 Christopher Ainsley
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.consoli.x2a;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-efforts, inefficient and incomplete converter from xdoc notation to asciidoc notation.<br/><br/>
 * 
 * Avoided using a real parser, so there are likely some bugs with tokenization but have tested it over 500K of my 
 * own documents and the conversion is about 99% good.<br/><br/>
 * 
 * NOTE : There are issues with asciidoc relating to stateful escaping of various elements. That is a backslash escape sequence
 * may sometimes be an escape token or it may sometimes be interpreted as a string literal, even when prefixing the exact same
 * character in the exact same mode. This is because certain characters (such as '#') trigger look forwards, activating a new parse
 * mode. To summarise, there are lots of instances of statefulness in asciidoc, therefore there may be some cases where special characters
 * creep through without escaping. I advise use of AsciiDocFX to preview the document and manually correct the output of this conversion.
 * 
 * Only currently works on a subset of the XDoc notation.
 * @author Chris Ainsley
 *
 */
public class XDoc2AsciiDoc {
	
	private static final String STUNT_INDEX_ASC = "stunt_index.asc";

	private static final int MAX_RECURSION = 100;

	static final String CHAPTER_PATTERN_STR = "\\s*(chapter|section|section2|section3):([^\\)]+)\\[([^\\]]+)\\]\\s*";
	
	static final String CHAPTER_PATTERN_STR_2 ="\\s*(chapter|section|section2|section3)\\[([^\\]]+)]\\s*";
	
	static final String IMAGE_PATTERN_STR = "img\\[([^\\]]+)\\]\\[(?:[^\\]]*)\\]\\[(?:[^\\]]*)\\]\\[(?:[^\\]]*)\\](.*)";
	
	static final String REF_PATTERN_STR = "ref\\:([^\\[\\]]+)\\[([^\\[\\]]+)\\](.*)";
	static final String LINK_PATTERN_STR = "link\\[([^\\[\\]]+)\\]\\s*\\[([^\\[\\]]+)\\](.*)";
	
	static final String HEADER_PATTERN_STR = "(document|authors|chapter-ref)\\[([^\\[\\]]+)\\]\\s*";
	
	static Pattern CHAPTER_PATTERN = Pattern.compile(CHAPTER_PATTERN_STR);
	static Pattern CHAPTER_PATTERN_2 = Pattern.compile(CHAPTER_PATTERN_STR_2);
	static Pattern IMAGE_PATTERN = Pattern.compile(IMAGE_PATTERN_STR);
	static Pattern REF_PATTERN = Pattern.compile(REF_PATTERN_STR);
	static Pattern LINK_PATTERN = Pattern.compile(LINK_PATTERN_STR);
	static Pattern HEADER_PATTERN = Pattern.compile(HEADER_PATTERN_STR);
	
	boolean isWithinOn         = false;
	boolean isWithinCodeBlock  = false;
	// NOTE :: We don't deal with embedded tables at the moment
	boolean isWithinTable      = false;
	boolean isWithinTableRow   = false;
	boolean isWithinLink       = false;
	boolean isWithinTableData  = false;
	boolean isWithinList       = false;
	boolean isWithinListItem   = false;
	boolean isOrderedList      = false;
	boolean isWithinEmphasis   = false;

	
	File inputFolder  = null;
	File outputFolder = null;
    
    public XDoc2AsciiDoc(File inputFolder, File outputFolder) {
		this.inputFolder  = inputFolder;
		this.outputFolder = outputFolder;
	}

	public static final String fileToString(File file, String charset) {
    	String retVal = null;
    	InputStream is = null;
    	
    	try {
			is = new FileInputStream(file);
			retVal = inputStreamToString(is, file.getAbsolutePath(), charset);
		} catch (FileNotFoundException e) {
			return null;
		}
		
		return retVal;
    }

	
	private static void stringToFile(String text, File file, String backupExistingFileSuffix, boolean okIfFileAlreadyExists) throws IOException {
		
		if (file.exists()) {
			if (!okIfFileAlreadyExists) {
				throw new IOException("Cannot store text in file '" + file.getAbsolutePath() + "' as already exists.");
			}
			
			if (backupExistingFileSuffix != null) {
				File origFile = file;
				int count=0;
				while (file.exists()) {
					file = new File(origFile.getParentFile(), origFile.getName() + backupExistingFileSuffix + (count == 0 ? "" : "." + count));
					count++;
				}
				
				boolean couldRename = origFile.renameTo(file);
				
				if (!couldRename) {
					throw new IOException("Could not rename file  '" + origFile.getAbsolutePath() + "' to '" + file.getAbsolutePath() + "'");
				}
				file = origFile;
			} else {
				file.delete();
			}
		}
		
		BufferedWriter bw = null;
		
		boolean exceptionOccurred = false;
		
		try {
			bw = new BufferedWriter(new OutputStreamWriter( new FileOutputStream(file), "UTF-8"));
			bw.write(text);
		} catch (IOException e) {
			exceptionOccurred = true;
			throw e;
		} finally {
			if (bw != null) {
				try {bw.flush();} catch (IOException e) {}
				try {bw.close();} catch (IOException e) { if (!exceptionOccurred) {throw e;}}
			}
		}
	}
    /**
     * Loads a text file into a string. A convenience method. Please be careful not to load too large a file
     * into a string otherwise an out of memory runtime exception may occur. The input stream is always closed.
     * @param inputStream The inputstream of the file to load into string
     * @param url Used only for logging purposes (reference to where this file was originally sourced from on classpath or filepath)
     * @return Returns null if the file could not be loaded into memory
     */
	private static final String inputStreamToString(InputStream inputStream, String url, String charset) {
        String textFileString = null;
        try {
            BufferedReader bufferedReader = new BufferedReader(charset == null ? new InputStreamReader(inputStream) : new InputStreamReader(inputStream, charset));
            StringWriter sw = new StringWriter();
            String nextLine = null;
            while (  (nextLine = bufferedReader.readLine()) != null  ) {
                sw.write(nextLine);
                sw.write("\n");
            }
            bufferedReader.close();
            sw.flush();
            sw.close();
            textFileString = sw.getBuffer().toString();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
        }
        return textFileString;
    }

	private static String moveForwardToFirstNonWhitespace(String inString, int numMoveForward) {
		String retVal = moveForward(inString, numMoveForward);
		
		if (retVal == null) {
			return null;
		}
		
		for (int i=0; i < retVal.length(); i++) {
			char currentChar = retVal.charAt(i);
			if (currentChar != ' ' && currentChar != '\t') {
				if (i == 0) {
					return retVal;
				} else {
					return retVal.substring(i);
				}
			}
		}
		return null;
	}

	protected static String moveForward(String inString, int numMoveForward) {
		String retVal = inString.substring(numMoveForward);
		
		/**
		 * Inefficient here, just simple to write
		 */
		
		if (retVal.length() == 0 || retVal.trim().length() == 0) {
			return null;
		}
		return retVal;
	}

	private void handleSectionsWithReferenceMarkers(String[] matches, StringBuilder sb, File outputFile) throws Exception {
		sb.append("[id=\""+matches[2]+"\"]"  + "\n");
		
		String type = matches[1];
		
		String prefix = "";
		
		if ("chapter".equals(type)) {
			// Chapters are one level down in asciidoc
			// The document title is the first level ... 
			prefix = "## ";
			_chapterIdToFileIdMap.put(matches[2], outputFile.getName());
		} else if ("section".equals(type)) {
			prefix = "### ";
		} else if ("section2".equals(type)) {
			prefix = "#### ";
		} else if ("section3".equals(type)) {
			prefix = "##### ";
		} else {
			throw new Exception("Invalid type : " + type);
		}
		
		sb.append(prefix + matches[3] + "\n");
	}
	
	
	HashMap<String, String> _chapterIdToFileIdMap = new HashMap<String, String>();
	
	private void handleSectionsWithReferenceMarkers2(String[] matches, StringBuilder sb, File outputFile) throws Exception {
		String type = matches[1];
		
		String prefix = "";
		
		if ("chapter".equals(type)) {
			// Chapters are one level down in asciidoc
			// The document title is the first level ... 
			prefix = "## ";
			
			_chapterIdToFileIdMap.put(matches[2], outputFile.getName());
			
		} else if ("section".equals(type)) {
			prefix = "### ";
		} else if ("section2".equals(type)) {
			prefix = "#### ";
		} else if ("section3".equals(type)) {
			prefix = "##### ";
		} else {
			throw new Exception("Invalid type : " + type);
		}
		
		sb.append(prefix + matches[2] + "\n");
	}
	
	private void handleHeaderItem(String[] matches, StringBuilder sb, File outputFile) throws Exception {
		String type = matches[1];

		if ("document".equals(type)) {
			sb.append("= " + matches[2] + "\n");
		} else if ("authors".equals(type)) {
			sb.append( matches[2] + "\n");
			sb.append( ":doctype: book\n");
			sb.append( ":encoding: utf-8\n");
			sb.append( ":lang: en\n");
			sb.append( ":toc: left\n");
			sb.append( ":toclevels: 2\n");
			sb.append( ":numbered:\n");
			sb.append( "\n");
		} else if ("chapter-ref".equals(type)) {
			final String chapterId = matches[2];
			
			
			String fileName = _chapterIdToFileIdMap.get(chapterId);
			fileName = fileName == null ? chapterId : fileName;
			sb.append("include::" + fileName + "[]\n");
		
		} else {
			throw new Exception("Invalid header item : " + type);
		}
		
		
	}

	private static String[] match(Pattern p, String currentLine) {
		String[] groupText = null;
		Matcher matches = p.matcher(currentLine);
		if (matches.matches()) {
			int numGroups = matches.groupCount()+1;
			groupText = new String[numGroups];
			for (int i=0; i < numGroups;i++) {
				groupText[i] = matches.group(i);
			}
		}
		
		return groupText;
	}
	
	int _listItemDepth = 0;
	private void processFile(String inputFileAsString, File outputFile, boolean isHeaderFile) throws Exception {
		
		
		isWithinOn         = false;
		isWithinCodeBlock  = false;
		// NOTE :: We don't deal with embedded tables at the moment
		isWithinTable      = false;
		isWithinTableRow   = false;
		isWithinLink       = false;
		isWithinTableData  = false;
		isWithinList       = false;
		isWithinListItem   = false;
		isOrderedList      = false;
		isWithinEmphasis   = false;
		_listItemDepth     = 0;
		tableRow           = 0;
		
		
		BufferedReader br = null;
		
		StringBuilder sb = new StringBuilder();
		
		try {
			br = new BufferedReader(new StringReader(inputFileAsString));
		
			String currentLine = null;

			StringBuilder dataCellSB = new StringBuilder();
			StringBuilder listItemSB = new StringBuilder();
			
			while ( (currentLine = br.readLine()) != null ) {
				handleLine(currentLine, sb, dataCellSB, listItemSB, 0, outputFile);
			}
			
			
		} catch (Exception e) {
			throw e;
		} finally {
			try { br.close();} catch (Exception e) {e.printStackTrace();}
		}
		
		if (isHeaderFile) {
			sb.append("include::"+STUNT_INDEX_ASC+"[]\n");
		}
		
		stringToFile(sb.toString(), outputFile, null, true);
	}
	
	int tableRow = 0;
	private static int findIndexOfNextReservedToken(String currentLine) {

		if (currentLine.startsWith("]")) {
			return 0;
		}
		int retVal = Integer.MAX_VALUE;
		
		int indexOf;
		/**
		 * Slow implementation - I know this
		 */
		
		indexOf = currentLine.indexOf("e[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}

		indexOf = currentLine.indexOf("td[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}

		indexOf = currentLine.indexOf("tr[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}

		indexOf = currentLine.indexOf("table[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}

		indexOf = currentLine.indexOf("ref:");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		
		indexOf = currentLine.indexOf("img[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		indexOf = currentLine.indexOf("ol[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		indexOf = currentLine.indexOf("ul[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		indexOf = currentLine.indexOf("item[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		
		indexOf = currentLine.indexOf("link[");
		if (indexOf != -1 && indexOf < retVal) {
			retVal = indexOf;
		}
		
	
		int numChars = currentLine.length();
		
		boolean previousWasBackslash = false;
		
		for (int i=0; i < numChars; i++) {
			char currentChar = currentLine.charAt(i);
			
			if (currentChar == '\\') {
				previousWasBackslash = true;
			} else {
				if (currentChar == ']') {
					if (!previousWasBackslash) {
						if (i < retVal) {
							retVal = i;
							break;
						}

					}
					
				}
				previousWasBackslash = false;
			}
		}
		
		
		return retVal == Integer.MAX_VALUE ? -1 : retVal;
	}
	
	private String escapeRegularText(String text) {
		if (isWithinTableData) {
			return text.replace("|", "\\|").replace("\\[", "[").replace("\\]", "]");
		} else {
			return text.replace("\\[", "[").replace("\\]", "]");
		}
	}
	
	protected void handleLine(String currentLine, StringBuilder sb, StringBuilder dataCellSB, StringBuilder listItemSB, int depth, File outputFile) throws Exception {

		if (depth == MAX_RECURSION) {
			throw new Exception("Recursion exception .... debugger time ... ");
		}
		
		String[] matches = null;
		
		if ((matches = match(CHAPTER_PATTERN, currentLine)) != null) {
			handleSectionsWithReferenceMarkers(matches, sb, outputFile);
		} else if ((matches = match(CHAPTER_PATTERN_2, currentLine)) != null) {
			handleSectionsWithReferenceMarkers2(matches, sb, outputFile);
		} else if ((matches = match(HEADER_PATTERN, currentLine)) != null) {
			
			handleHeaderItem(matches, sb, outputFile);
			
		} else if (currentLine.startsWith("on[")){
			isWithinOn = true;
			sb.append("----\n");
		} else if (currentLine.startsWith("code-raw[") || currentLine.startsWith("code[")){
			isWithinCodeBlock = true;
			sb.append("----\n");
		} else if (currentLine.startsWith("ol[") || currentLine.startsWith("ul[")) {
			isWithinList = true;
			isOrderedList = currentLine.equals("ol[");
			sb.append("[options=\"compact\"]\n" );
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 3);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}
		} else if (currentLine.startsWith("tr[")) {
			isWithinTableRow = true;
			tableRow++;
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 3);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}
		} else if (currentLine.startsWith("e[")) {
			isWithinEmphasis = true;
			sb.append("*" );
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 2);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}
		} else if (currentLine.startsWith("item[")) {
			
			sb.append((isOrderedList ? "1. " : "* "));
			
			isWithinListItem = true;
			
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 5);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}
			
			
			
		} else if (currentLine.startsWith("td[")) {
			sb.append("| ");
			isWithinTableData = true; // THIS DOES NOT WORK FOR TABLES EMBEDDED WITHIN TABLES -- WE NEED A CONTEXT STACK FOR THAT... 
			
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 3);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}
			
		} else if (currentLine.startsWith("ref:")) {
			matches = match(REF_PATTERN, currentLine);
			
			if (matches == null) {
				throw new IOException("Invalid reference pattern");
			}
			
			
			final int currentLineLength = currentLine.length();
			final int remainderOfLineLength = matches[3].length();
			final int lengthOfMatch = currentLineLength - remainderOfLineLength;
			
			// Adds extra space if the link is at the beginning of a table row line
			// Logic not perfect here .... (not checking for escaped '|' chars)
			
			if (isWithinTableData && depth == 0 && (sb.charAt(sb.length()-2) != '|') ) {
				sb.append(" ");
			}
			
			sb.append("<<" + matches[1] + "," + matches[2] +">>");
			
			String newLine = moveForward(currentLine, lengthOfMatch);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			} else {
				sb.append("\n");
			}
			
		} else if (currentLine.startsWith("link[")) {
			matches = match(LINK_PATTERN, currentLine);
			
			if (matches == null) {
				throw new IOException("Invalid link pattern : " + currentLine);
			}
			
			
			final int currentLineLength = currentLine.length();
			final int remainderOfLineLength = matches[3].length();
			final int lengthOfMatch = currentLineLength - remainderOfLineLength;
			
			// Adds extra space if the link is at the beginning of a table row line
			// Logic not perfect here .... (not checking for escaped '|' chars)
			
			if (isWithinTableData && depth == 0 && (sb.charAt(sb.length()-2) != '|') ) {
				sb.append(" ");
			}
			
			sb.append("link:" + matches[1] + "[" + matches[2] +"]");
			
			String newLine = moveForward(currentLine, lengthOfMatch);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			} else {
				sb.append("\n");
			}
			
			
		} else if (currentLine.startsWith("img[")) {
			
			matches = match(IMAGE_PATTERN, currentLine);
			
			if (matches == null) {
				throw new IOException("Invalid images pattern");
			}
			
			final int currentLineLength = currentLine.length();
			final int remainderOfLineLength = matches[2].length();
			final int lengthOfMatch = currentLineLength - remainderOfLineLength;
			
			sb.append("image:" + matches[1] + "[align=\"center\"]");
			
			String newLine = moveForward(currentLine, lengthOfMatch);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			} else {
				sb.append("\n");
			}
		} else if (currentLine.startsWith("table[")) {
			isWithinTable = true;
			sb.append("|========\n");
			
			String newLine = moveForwardToFirstNonWhitespace(currentLine, 6);
			if (newLine != null) {
				handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
			}

		} else if (currentLine.startsWith("]") && ((isWithinOn || isWithinCodeBlock)  )){
			sb.append("----\n");
			isWithinOn = false;
			isWithinCodeBlock = false;
		} else if (currentLine.startsWith("]") && (!(isWithinOn || isWithinCodeBlock)  )){
			
			if ((isWithinOn || isWithinCodeBlock || isWithinTable || isWithinTableRow || isWithinList || isWithinListItem || isWithinEmphasis)) {
				
				
				boolean isAddLinefeedOnEOL = false;
				boolean isDisgardWhitespaceThatFollows = true;
				if (isWithinEmphasis) {
					sb.append("*");
					isWithinEmphasis = false;
					isAddLinefeedOnEOL = true;
					isDisgardWhitespaceThatFollows = false;
				} else {
					if (isWithinListItem) {
						isAddLinefeedOnEOL = true;
						isWithinListItem = false;
					} else {
						if (isWithinList) {
							sb.append("\n");
							isWithinList = false;
						} else {
							
							if (isWithinTableData) {
								isWithinTableData = false;
							} else {
							
								if (isWithinTableRow) {
									sb.append("\n");
									isWithinTableRow = false;
									tableRow--;
								} else {
									if (isWithinTable) {
										sb.append("|========\n");
										isWithinTable = false;
										tableRow = 0;
									} else {
										sb.append("----\n");
										isWithinOn = false;
										isWithinCodeBlock = false;
									}
								}
							}
						}
					}
				}
				
				String newLine = isDisgardWhitespaceThatFollows ? moveForwardToFirstNonWhitespace(currentLine, 1) : moveForward(currentLine, 1);
				if (newLine != null) {
					handleLine(newLine, sb, dataCellSB, listItemSB, depth+1, outputFile);
				} else if (isAddLinefeedOnEOL) {
					sb.append("\n");
				}
			}
			
			
		} else {
			if (isWithinOn || isWithinCodeBlock) {

				/**
				 * Works around an issue where if the code snippet ends in the same line as other content
				 * then we put the code block end marker on the same line as the content, which is no good.
				 * 
				 * This workaround doesn't work if immediately after a code block, more content is added on the same
				 * line (which never happens in my documents, but is a possibility).
				 */
				
				if (currentLine.endsWith("]") && currentLine.length() > 1 && currentLine.charAt(currentLine.length()-2) != '\\') {
					sb.append(currentLine.substring(0, currentLine.length()-1).replace("\\[", "[").replace("\\]", "]")).append("\n");
					handleLine("]", sb, dataCellSB, listItemSB, depth+1, outputFile);
				} else {
					sb.append(currentLine.replace("\\[", "[").replace("\\]", "]")).append("\n");	
				}
				
			} else {
				
				int indexOfNextReservedToken = findIndexOfNextReservedToken(currentLine);
				
				if (indexOfNextReservedToken == -1) {
					
					// TODO :: We only need to escape the first special character per line which is 
					// hard to calculate as we are dealing with text buffers only ... That is,
					// If when writing a line we simply escape all # chars, then we run the risk
					// of escaping legitimate characters... 
					
					// The way to resolve this problem is by using smart buffers, but
					// I can't justify re-writing everything so users will just have to manually escape
					// the output if there is a problem here ... 
					
					///*.replace("#", "\\#"))*/
					
					if (depth == 0) {
						currentLine = trimLeadingWhitespace(currentLine);
					}
					
					final String escapeRegularText = escapeRegularText(currentLine);
					sb.append(escapeRegularText);
					

					
					if (!isWithinTableData) {
						sb.append("\n");
					}
				} else {
					String s1 = currentLine.substring(0, indexOfNextReservedToken);
					if (depth == 0) {
						s1 = trimLeadingWhitespace(s1);
					}

					final String s2 = currentLine.substring(indexOfNextReservedToken);

					
					final String escapeRegularText = escapeRegularText(s1);
					sb.append(escapeRegularText);
					handleLine(s2, sb, dataCellSB, listItemSB, depth+1, outputFile);
				}

			}
		}
	}
	

	private String trimLeadingWhitespace(String str) {
		final int length = str.length();
		
		for (int i=0; i < length; i++) {
			char currentChar = str.charAt(i);
			if (currentChar != ' ' && currentChar != '\t') {
				if (i == 0) {
					return str;
				} else {
					return str.substring(i);
				}
			}
		}
		
		return "";
	}

	public synchronized void execute() throws Exception {
		File[] files = inputFolder.listFiles(new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				final String name = pathname.getName();
				if (pathname.isFile() && name.endsWith(".xdoc")  ) {
					return true;
				} else {
					return false;
				}
			}
		});
	
		String[] fileContentArray = new String[files.length];
		
		int indexOfDocumentFile = -1;
		
		for (int i = 0 ; i < files.length; i++) {
			File f = files[i];
			String inputFileAsString = fileToString(f, "UTF8");
			
			if (inputFileAsString.startsWith("document")) {
				if (indexOfDocumentFile != -1) {
					throw new Exception("Two or more files cannot start with 'document' in the same folder : " + f.getAbsolutePath() + ", " + files[indexOfDocumentFile].getAbsolutePath());
				}
				indexOfDocumentFile = i;
			}
			fileContentArray[i] = inputFileAsString;
		}
		
		/**
		 * Read all files before the header file, so that we have a table of sections to files
		 */
		for (int i = 0 ; i < files.length; i++) {
			if (indexOfDocumentFile != i) {
				File f = files[i];
				System.out.println("Processing : " + f.getAbsolutePath());
				String s = fileContentArray[i];
				processFile(s, new File (outputFolder, calcOutputFilename(f)), false);
				fileContentArray[i] = null;
			}
		}
		
		
		
		if (indexOfDocumentFile != -1) {
			
			stringToFile("[index]\n== Dummy Index", new File (outputFolder,STUNT_INDEX_ASC), null, true);
			
			File f = files[indexOfDocumentFile];
			System.out.println("Processing : " + f.getAbsolutePath());
			String s = fileContentArray[indexOfDocumentFile];
			processFile(s, new File (outputFolder, calcOutputFilename(f)), true);
		}
		
		
		
		
		System.out.println("All "+files.length+" files processed OK... ");
	}

	protected String calcOutputFilename(File f) {
		return f.getName().substring(0, f.getName().length()-5).replace(" ", "_").replace("-", "_") + ".asc";
	}
	
	
	public static void main(String[] args) {

		File inputFolder = null;
		File outputFolder = null;
		
		try {
			if (args.length != 2) {
				System.out.println("Usage : java " + XDoc2AsciiDoc.class.getName() + "[folder containing xdocs] [output folder in which to place asciidocs]");
				return;
			} else {
				inputFolder  = new File(args[0]);
				outputFolder = new File(args[1]);
			}
			
			if (!inputFolder.isDirectory()) {
				throw new Exception ("Invalid input directory...");
			}
			
			if (!outputFolder.isDirectory()) {
				throw new Exception ("Invalid output directory...");
			}
			System.out.println("");
			System.out.println("NOTE :: This tool is only meant to help in migrating xdoc documents");
			System.out.println("        to asciidoc format. The resulting documents will likely still");
			System.out.println("        require manual fixes.");
			System.out.println("");
			XDoc2AsciiDoc converter = new XDoc2AsciiDoc(inputFolder, outputFolder);
			
			converter.execute();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}


}
