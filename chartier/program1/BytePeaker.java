import java.io.FileInputStream;
import java.io.IOException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/*
 * Heuristics: Since solving this problem involves methods beyond the scope of this course, I have to implement heuristic solution.
 * Conditions
 * 1: Any method with the classifier "Synchronized" in the method header flags visitMethod to count any, and all, instructions within
 * the method as synchronized. Note: When a method contains a synchronized section, there is a forced monitorexit at the end of the instruction set.
 * Therefore, when a synchronized method contains nested synchronized instructions, all instructions are considered to be synchronized as well as the 
 * forced monitorexit segment.
 * 2. when there is a loop present, ASM fails to recognize the loop comparison bytecode instruction. When a loop is present, it contains the command
 * if_icmpx (where x stands for a variant, which decimal values range from 160 to 165) which is used in the base case comparison. When the program 
 * reads the if_cmpx command, it supplements the program with a +1 sync count and a +1 instruction total count, if within a synchronized section.
 * If not within a synchronized section, it will only increment the total instruction count.
 * 
 * The synchronized sections are flagged by a counter, which is incremented when hitting a monitorenter and decremented when hitting a monitorexit.
 * When the counter reaches -1, the instructions being visited reside in the forced monitorexit (see above).
 * 
 * An inspectIns() method is called at each instruction visit, where it increments the total instruction count and also the synced instruction
 * count (if indeed it is).
 */

// This class allows the inspection of each method that is visited by the BytePeaker class.
 
class methodDoer extends MethodVisitor implements Opcodes
{
	static int ic = 0;//counts total instructions
	static int syncedCode = 0;
	static boolean inSynMethod = false;//flags a sycned method.
	
	static int monitorCount = 0; // 1+ = in sync method. 0 = not in sync method.
	
	//static int monitorExits = 0;//debug
	//static int monitorEnters = 0;//debug
	
	public methodDoer(int api, int access, String name, String desc, String signature, String[] exceptions, boolean isSyncMethod ) 
		{
			super(api);
			
			inSynMethod = isSyncMethod;//force reset of sync method flag
		}
		
		@Override
		//Visits zero-operand instruction
		//Note: monitorenter and monitorexit are both zero-operand instructions, so their presence is checked here only.
		public void visitInsn(int opcode)
		{
			//System.out.println("reading a zero operand instruction, ins is " + opcode);
			if(isMonitorEnter(opcode)&& (!inSynMethod))//dont double count monitorenter!
			{
				
				//monitorEnters++;
				syncedCode++;//ensure that the monitor is included
				monitorCount++;
				//System.out.println("monitorCount: " + monitorCount);
				
			}
			if(isMonitorExit(opcode)&& (!inSynMethod))
			{
				//monitorExits++;
				monitorCount--;//decrement the counter
				//System.out.println("monitorcount: " + monitorCount);
				/*
				 * Note: the try/catch monitorExit(which double checks the resource is unlocked) will create an unmatched 
				 * monitorExit. This step catches that, doesnt count it, and offsets the impact by setting monitorCount to 0.
				 */
			    if(monitorCount == -1)//indicates end of class, the catch block monitorexit
				{
			    	monitorCount = 0;//offset and reset. see above note.
				}
				else 
				{
					syncedCode++;//ensure this monitor counts as sycned code
				}
			}
			if(inSynMethod)
			{
				syncedCode++;
			}
			else if((monitorCount >0 ) && (!isMonitorEnter(opcode) && (!isMonitorExit(opcode))))
				//if in a segment, and the opcode isnt enter or exit (which were already handled above)
			{
				syncedCode++;
			}
			//System.out.println(" synced ins: " + syncedCode);
			
			ic++;
			//System.out.println(" total ins: " + ic);
			
		}
		@Override
		public void visitIntInsn(int opcode, int operand)
		{
			inspectIns();
		}
		@Override
		//visits var instruction of a method
		public void visitVarInsn(int opcode, int var)
		{
			inspectIns();
		}
		
		@Override
		//visits type instruction of a method
		public void visitTypeInsn(int opcode, String desc)
		{
			inspectIns();
		}
		@Override
		//visits field instruction of a method
		public void visitFieldInsn(int opcode, String owner, String name, String desc)
		{
			inspectIns();
		}
		@Override
		//visits a method instruction
		public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean b )
		{
			inspectIns();
		}
		@Override
		//visits dynamic instruction in a method. Not used.
		public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs)//even used?
		{
			//System.out.println("reading a dynamic instruction named " + name +" described by " + desc);
			inspectIns();
			//System.out.println(" total ins: " + ic);
		}
		@Override
		public void visitJumpInsn(int opcode, Label label)
		{
			//System.out.println("reading a jump instruction, ins is " + opcode);
		
			if(inSynMethod)
			{
				if((opcode > 159) && (opcode < 166))
				//in a synch method, and its one of the if_cmpx.... make up for the missed instruction
				//Note: total instruction increment that accompanies syncedCode++ is handled at the end of method.
				{
					syncedCode++;
				}
				syncedCode++;
			}
			else if((monitorCount >0 ) && (!isMonitorEnter(opcode) && (!isMonitorExit(opcode))))
				//if in a segment, and the opcode isnt enter or exit (which were already handled above)
			{
				syncedCode++;
			}
			
			if(((opcode > 159) && (opcode < 166)) && ((monitorCount>0)))//heuristic offset
			//ASM missed the iinc bytecode instruction, which is found in a loop
			//+1 to sync and total count, but only if in a sync section...
			{
				syncedCode++;
				ic++;
			}
			else if((opcode > 159) && (opcode < 166))//165 to 160.
			//if not in a sync section, dont +1 sync, just +1 the total count.
			{
				ic++;
			}
			ic++;
			//System.out.println(" synced ins: " + syncedCode);
			//System.out.println(" total ins: " + ic);
		}
		
		public void visitEnd()
		{
			
		}
		//Helper method to reduce code clutter. checks if opcode is monitorenter
		public boolean isMonitorEnter(int opcode)
		{
			if(opcode == 194)//is a monitorenter
			{
				return true;
			}
			return false;
		}
		//Helper method to reduce code clutter. checks if opcode is monitorexit.
		public boolean isMonitorExit(int opcode)
		{
			if(opcode == 195)//is a monitorexit
			{
				return true;
			}
			return false;
		}
		//Called at each instruction visit. Checks if in a synced method, and increments if so. If not, it checks to see
		//if its in a synchronized segment. 
		public void inspectIns()
		{
			if(inSynMethod)
			{
				syncedCode++;
			}
			else if(monitorCount >0 )
				//if in a synchronized segment
			{
				syncedCode++;
			}
			ic++;
			//System.out.println(" synced ins: " + syncedCode);
			//System.out.println(" total ins: " + ic);
		}
}

/*
 * A class that reads in a .class (bytecode) file and counts the number of total instructions that are protected by the 
 * Synchronized commands. It then calculates the percent of code within these commands and prints it to the user.
 * 
 * Note: Monitorenter and Monitorexit are both zero operand instructions, therefore only one method needs to check for them: visitIns();
 */
public class BytePeaker extends ClassVisitor implements Opcodes {

	
	//public static int methodInsNum = 0;//raw method ins, debug
	public static int fieldInsNum = 0;//raw field ins, added to total ins
	private boolean flagSync = false;//used to tell the methodVisitor to count all contents as synced.
	//public static int syncMethodsCount = 0;//num of sycn methods
	methodDoer md;//created here for scope 
	/*
	 * The actual calculations are done in visitEnd, therefore md must be declared here for scope purposes.
	 */
	
	public BytePeaker(int api) {
		super(ASM5);
	}
	
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
	{

	}
	
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) 
	{
		if((access & Opcodes.ACC_SYNCHRONIZED) > 0)//identifies a method that is synchronized
		{
			//System.out.println("Synnnnnn"); //debug
			flagSync = true;//flag this method as synchronized 
		}
		else
		{
			flagSync = false;
		}
		/*
		 * Allows inspection of each method body.
		 * Note: if a method is seen to be synchronized itself, the flagSync boolean will turn true and alert
		 * the inspected method to count all instructions as synchronized.
		 */
		methodDoer md = new methodDoer(ASM5,access, name, desc, signature, exceptions, flagSync);
		
		return md;
		
	}
	
	public void visitInnerClass(String name, String outerName, String innerName, int access)
	{
		//System.out.println(" " + name + outerName + " " + innerName); 
	}
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value)
	{
		//System.out.println("Visited a field " + name + desc); //debug
		fieldInsNum++;
		return null;
	}

	
	public void visitEnd()
	{
		/*debug prints
		System.out.println("}");
		System.out.println("total method ins " + methodDoer.ic);
		System.out.println("Total field vars " + fieldInsNum);
		System.out.println("Total functional monitorEnters: " + methodDoer.monitorEnters);
		System.out.println("Total functionalmonitorExits: " + methodDoer.monitorExits);
		System.out.println("There is total of " + syncMethodsCount + " Synchronized methods");
		System.out.println("");
		*/
		
		int syncedInsTot = methodDoer.syncedCode;
		int totalIns = methodDoer.ic;
		double percentSynced = 0.0f;
		if(totalIns < 1)//avoid div/0
		{
			percentSynced = 0.0;//hard reset, in case...
		}
		else
		{
			//cast ints to doubles for print
			percentSynced = ((double)syncedInsTot/(double)totalIns)* 100.0;
		}
		
		//round it to two decimals, taken from http://stackoverflow.com/questions/5710394/how-do-i-round-a-double-to-two-decimal-places-in-java
		percentSynced = Math.round(percentSynced * 100);
		percentSynced /= 100;
		
		//System.out.println("Total Synchronized instructions: " + syncedInsTot + " Total instructions: " + totalIns + " , percent sycned: " + percentSynced +"%");
		
		System.out.println(totalIns + "    " + syncedInsTot + "    " + percentSynced + "%");
	}


	/*
	 * Main creates an instance of its class, passing the opcodes used in ASM5, and creates a ClassReader to read a class from
	 * the command line. The ClassReader then accepts the ClassVisitor-like instance.
	 */
	public static void main(String[] args) throws IOException 
		{
			BytePeaker bp = new BytePeaker(Opcodes.ASM5);
			ClassReader cr = new ClassReader(new FileInputStream(args[0]));//reads in file name and path
			cr.accept(bp, 0);		
		}

}
