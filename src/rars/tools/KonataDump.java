/*
Copyright (c) 2024, Hadrien Moulherat

Developed by Hadrien Moulherat (hadrien.moulherat@etudiant.univ-rennes.fr)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

package rars.tools;

import java.awt.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import rars.ProgramStatement;
import rars.riscv.BasicInstruction;
import rars.riscv.BasicInstructionFormat;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.instructions.Branch;

/**
 * RARS Tool for Visualizing Execution Pipeline with <a href="https://github.com/shioyadan/Konata/tree/master">Konata Software</a>.
 * This tool allows the creation of logfiles for the executed program in RARS. Logfiles
 * can be opened and visualized using Konata software, providing insights into the
 * program's execution pipeline.
 * Branch hazards and data hazards are handled by the tool to ensure accurate visualization.
 *
 * @author Hadrien Moulherat (hadrien.moulherat@etudiant.univ-rennes1.fr)
 */
public class KonataDump extends AbstractToolAndApplication {
    private static final int REGISTERS_NBR = 32;
    private static final String name = "Konata Log Dump";
    private static final String version = "0.42 (Hadrien Moulherat)";
    private static final String heading = "Pipeline visualizer";
    private final ArrayList<Cycle> pipeline;
    private final Queue<pipelineInstr> instructionQueue; //can be a single instruction of the pipeline.
    private final BitSet usedRD;
    private JTextArea logArea;
    private int globalCount;
    boolean dataHazard = false;
    boolean forwardUnit = false;
    //TODO: boolean defaultBranchBehaviour = false;
    private enum Stage {

        /*
        TODO: new stages can be added but i need to specify:
           - In witch stage the destination register is freed.
           - In witch stage the destination register need to check if a the register is free.
           - Code with Stage.XXX need to be adapted.
         */
        FETCH(2), DECODE(1), EXECUTE(1), MEMORY(1), WRITEBACK(2);
        private int value;

        Stage(int value) {
            this.value = value;
        }

        public int getValue() {
            return (value);
        }

        public void setValue(int newValue) {
            this.value = newValue;
        }
    }

    /**
     * Simple constructor, to be used by the RARS Tools menu.
     * Initialize the pipeline, instruction queue, used registers and instruction count.
     */
    public KonataDump() {
        super(name + "," + version, heading);
        instructionQueue = new LinkedList<>();
        pipeline = new ArrayList<>();
        usedRD = new BitSet(REGISTERS_NBR);
        initializePipeline();
        globalCount = 0;
    }

    /**
     * Create and initialize the pipeline following the size of each state of the enum Stage.
     * The pipeline is simulated with an ArrayList, each element of the ArrayList is a cycle in the pipeline.
     */
    private void initializePipeline() {
        for (Stage stage : Stage.values()) {
            addCyclesToPipeline(stage);
        }
    }

    /**
     * Add a state in the pipeline following the size of the state.
     *
     * @param stage the state to add in the pipeline.
     */
    private void addCyclesToPipeline(Stage stage) {
        for (int i = 0; i < stage.value; i++) {
            pipeline.add(new Cycle(stage));
        }
    }

    @Override
    protected JComponent buildMainDisplayArea() {
        Box tool = Box.createVerticalBox();

        tool.add(pipelineSettings());
        tool.add(logSection());
        tool.add(dumpSection());
        initLog();
        return (tool);
    }

    private JPanel pipelineSettings() {
        JPanel pipelinePanel = new JPanel(new GridLayout(2, 0));
        TitledBorder pipelineTitle = new TitledBorder(" Pipeline Settings ");
        pipelineTitle.setTitleJustification(TitledBorder.CENTER);
        pipelinePanel.setBorder(pipelineTitle);

        for (Stage s: Stage.values()) {
            pipelinePanel.add(createPipelineSpinners( s.name()+" Cycle(s)", s));
        }

        //Avoid data hazard
        JCheckBox forwardBox = new JCheckBox("Forward Unit");
        forwardBox.addActionListener(actionEvent -> forwardUnit = forwardBox.isSelected());
        pipelinePanel.add(forwardBox);
        return (pipelinePanel);
    }

    private JPanel createPipelineSpinners(String label, Stage stage) {
        JPanel panel = new JPanel(new BorderLayout(2, 2));

        panel.setBorder(new EmptyBorder(4, 4, 4, 4));
        panel.add(new JLabel(label), BorderLayout.WEST);
        panel.add(pipelineSpinner(stage), BorderLayout.EAST);
        return panel;
    }

    private JSpinner pipelineSpinner(Stage stage) {
        SpinnerNumberModel spinnerModel = new SpinnerNumberModel(stage.value, 1, 8, 1);
        JSpinner spinner = new JSpinner(spinnerModel);
        spinner.addChangeListener(changeEvent -> {
            int spinnerValue = (int)spinner.getValue();

            if (stage.value < spinnerValue) {
                addCycle(stage);
            } else {
                removeCycle(stage);
            }
            stage.setValue(spinnerValue);
        });
        return spinner;
    }

    /**
     * Add a cycle of a state in the pipeline.
     *
     * @param stage the state of the cycle to add.
     */
    private void addCycle(Stage stage) {
        int idxStage = searchIndexStage(stage);

        if (idxStage != -1) {
            pipeline.add(idxStage, new Cycle(stage));
        }
    }

    /**
     * Remove a cycle of a state in the pipeline.
     *
     * @param stage the state of the cycle to remove.
     */
    private void removeCycle(Stage stage) {
        int idxStage = searchIndexStage(stage);

        if (idxStage != -1) {
            pipeline.remove(idxStage);
        }
    }

    /**
     * Search the index of the first occurrence of a state in the pipeline.
     *
     * @param stage the state to search in the pipeline.
     * @return the index of the first occurrence of state.
     */
    private int searchIndexStage(Stage stage) {
        for (int i = 0; i < pipeline.size(); i++) {
            if (pipeline.get(i).getStage() == stage) {
                return (i);
            }
        }
        return -1;
    }

    private JPanel logSection() {
        JPanel logPanel = new JPanel();
        TitledBorder logTitle = new TitledBorder(" Konata Log ");
        logTitle.setTitleJustification(TitledBorder.CENTER);
        logPanel.setBorder(logTitle);

        logArea = new JTextArea(30, 50);
        logArea.setEditable(false);
        logPanel.add(logArea);

        JScrollPane logScroll = new JScrollPane(logArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        logPanel.add(logScroll);
        return (logPanel);
    }

    private JPanel dumpSection() {
        JPanel dumpPanel = new JPanel();
        TitledBorder dumpTitle = new TitledBorder(" Dump Log ");
        dumpTitle.setTitleJustification(TitledBorder.CENTER);
        dumpPanel.setBorder(dumpTitle);

        JTextField dumpLogFilename = new JTextField("konataDump.log", 42);
        JButton dumpLogButton = getjButton(dumpLogFilename);
        dumpPanel.add(dumpLogButton);
        dumpPanel.add(dumpLogFilename);
        return (dumpPanel);
    }

    private JButton getjButton(JTextField dumpLogFilename) {
        JButton dumpLogButton = new JButton("Dump log");

        dumpLogButton.addActionListener(actionEvent -> {
            //Impossible to detect the end of a program.
            //If the logs are dumped, this loop will extract the remaining data in the pipeline.
            while (!isPipelineEmpty()) {
                pushInstructionIntoPipeline();
                pipelineIntoKonata();
            }

            String filename = dumpLogFilename.getText();
            try {
                if (filename.isEmpty()) {
                    dumpLogFilename.setBackground(Color.red);
                    return;
                }
                File file = new File(filename);
                BufferedWriter bwr = new BufferedWriter(new FileWriter(file));
                bwr.write(logArea.getText());
                bwr.flush();
                bwr.close();
                dumpLogFilename.setBackground(Color.green);
            } catch (IOException e) {
                dumpLogFilename.setBackground(Color.red);
            }
        });
        return dumpLogButton;
    }

    /**
     * Reset and initialize the logs with the mandatory konata header.
     */
    private void initLog() {
        resetLog();
        writeLog("Kanata\t0004\n");
        writeLog("C=\t0\n");
    }

    /**
     * Reset the logs.
     */
    public void resetLog() {
        logArea.setText(null);
    }

    /**
     * Add a string in the logs.
     * @param str the string to add in the logs.
     */
    public void writeLog(String str) {
        logArea.append(str + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /**
     * Check if the pipeline is empty or not.
     *
     * @return true if the pipeline is empty, otherwise false.
     */
    private boolean isPipelineEmpty() {
        for (Stage s: Stage.values()) {
            if (!isStageEmpty(s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a specific stage in the pipeline is empty or not.
     *
     * @param stage the stage to check if it is empty.
     * @return true if the stage is empty, otherwise false.
     */
    private boolean isStageEmpty(Stage stage) {
        for (Cycle c: pipeline) {
            if (c.getStage() != stage) {
                continue;
            }
            if (c.getInstruction() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Add the tool as an observer of the entire text segment in memory.
     * This method is called when the "Connect" button on a tool is pressed.
     */
    @Override
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
    }

    @Override
    protected void processRISCVUpdate(Observable resource, AccessNotice notice) {
        if (!notice.accessIsFromRISCV()) { return; }

        if (notice.getAccessType() == AccessNotice.READ && notice instanceof MemoryAccessNotice memAccNotice) {
            try {
                int addrStmt = memAccNotice.getAddress();
                ProgramStatement stmt = Memory.getInstance().getStatementNoNotify(addrStmt);

                if (stmt != null) {
                    handleInstruction(stmt);
                    handleBranchHazard(stmt, addrStmt);
                }
            } catch (AddressErrorException e) {
                throw new RuntimeException(e);
            }
        }
    }


    private void handleInstruction(ProgramStatement stmt) {
        pipelineInstr instruction = extractDataFromStatement(stmt);
        instructionQueue.offer(instruction);
        pushInstructionIntoPipeline();
        pipelineIntoKonata();
        globalCount++;
    }

    private void handleBranchHazard(ProgramStatement stmt, int addrStmt) throws AddressErrorException {
        if (stmt.getInstruction() instanceof Branch) {
            if (((Branch) stmt.getInstruction()).willBranch(stmt)) {
                handleBranchHazardInstructions(addrStmt);

                //Reset the stages until the Execute stage
                for (Cycle c: pipeline) {
                    if (c.getStage() == Stage.EXECUTE) { break ; }
                    c.setInstruction(null);
                }
            }
        }
    }

    private void handleBranchHazardInstructions(int addrStmt) throws AddressErrorException {
        int hazardSize;
        int tmp = globalCount;
        ProgramStatement hazardStmt;

        hazardSize = Stage.DECODE.getValue() + Stage.EXECUTE.getValue();
        for (int i = 0; i < hazardSize; i++) {
            hazardStmt = Memory.getInstance().getStatementNoNotify(addrStmt + (4 * (i + 1)));
            if (hazardStmt == null) {
                break; // No more instructions
            }
            handleInstruction(hazardStmt);
        }

        for (int i = 0; i < hazardSize; i++) {
            writeLog("R\t" + (tmp + i) + "\t0\t1");
        }
    }


    private pipelineInstr extractDataFromStatement(ProgramStatement stmt) {
        String strStmt = stmt.getBasicAssemblyStatement();

        int[] reg = stmt.getOperands();

        BasicInstruction instr = (BasicInstruction) stmt.getInstruction();
        BasicInstructionFormat format = instr.getInstructionFormat();
        switch (format) {
            case R_FORMAT:
                return new pipelineInstr(globalCount, strStmt,
                        reg.length > 0 ? reg[0] : -1,
                        reg.length > 1 ? reg[1] : -1,
                        reg.length > 2 ? reg[2] : -1);
            case I_FORMAT:
                if (strStmt.contains("csrrs")) {
                    return new pipelineInstr(globalCount, strStmt,
                            reg.length > 0 ? reg[0] : -1,
                            reg.length > 2 ? reg[2] : -1);
                } else {
                    return new pipelineInstr(globalCount, strStmt,
                            reg.length > 0 ? reg[0] : -1,
                            reg.length > 1 ? reg[1] : -1);
                }
            case S_FORMAT:
                return new pipelineInstr(globalCount, strStmt,
                        reg.length > 0 ? reg[0] : -1,
                        reg.length > 2 ? reg[2] : -1);
            case B_FORMAT:
                return new pipelineInstr(globalCount, strStmt,
                        -1,
                        reg.length > 0 ? reg[0] : -1,
                        reg.length > 1 ? reg[1] : -1);
            case U_FORMAT:
            case J_FORMAT:
                return new pipelineInstr(globalCount, strStmt,
                        reg.length > 0 ? reg[0] : -1,
                        -1);
            default:
                return new pipelineInstr(globalCount, strStmt, -1, -1);
        }
    }



    /**
     * Shifts instructions through the pipeline stages.
     * This method is a cornerstone of the tool, orchestrating the movement of instructions through
     * the various pipeline stages, handling data hazards, and ensuring the proper functioning of the pipeline.
     */
    private void pushInstructionIntoPipeline() {
        pipelineInstr instr;

        clearLastCycleRegister();
        rightShiftInstruction();
        //If the FETCH stage is not empty then no instructions enter the pipeline, we pass a cycle recursively.
        if (!(isStageEmpty(Stage.FETCH))) {
            pipelineIntoKonata();
            pushInstructionIntoPipeline();
        }
        //Otherwise we add a new instruction to the pipeline and remove it from the queue.
        if (!instructionQueue.isEmpty()) {
            instr = instructionQueue.poll();
            pipeline.getFirst().setInstruction(instr);
        }
    }

    private void clearLastCycleRegister() {
        int rd;
        Cycle lastCycle = pipeline.getLast();
        pipelineInstr lastInstruction = lastCycle.getInstruction();

        if (lastInstruction != null) {
            rd = lastInstruction.getRd();
            if (rd != -1) { usedRD.clear(rd); }
        }
    }

    private void rightShiftInstruction() {
        int rd;
        pipelineInstr instr;
        Cycle currentCycle;
        Cycle previousCycle;

        //We shift each element of the pipeline to the right.
        for (int i = (pipeline.size() - 1); i > 0; i--) {
            currentCycle = pipeline.get(i);
            previousCycle = pipeline.get(i - 1);

            //If there is no instruction if the previous cycle we can skip.
            //We don't skip if it's the first iteration to free the last cycle from pipeline.
            if (i != pipeline.size() - 1 && previousCycle.getInstruction() == null) {
                continue ;
            }

            if (currentCycle.getStage() != previousCycle.getStage() && i != pipeline.size() - 1) {
                shiftDifferentStages(previousCycle, currentCycle);
            } else {
                currentCycle.setInstruction(previousCycle.getInstruction());
                previousCycle.setInstruction(null);
            }
        }
    }

    /**
     * Code where data hazard is detected and handled.
     * @param previousCycle
     * @param currentCycle
     */
    private void shiftDifferentStages(Cycle previousCycle, Cycle currentCycle) {
        int rd = previousCycle.getInstruction().getRd();;

        //If the stage isn't empty we block the shifting, because there is already an instruction.
        if (!isStageEmpty(currentCycle.getStage())) { return ; }
        //If the destination register is used by another instruction in the pipeline, we need to freeze the pipeline.
        //This is called a data hazard.
        if (previousCycle.getStage() == Stage.DECODE && currentCycle.getStage() == Stage.EXECUTE
                && previousCycle.getInstruction() != null) {
            if (rd != -1 && usedRD.get(rd) && !forwardUnit) {
                dataHazard = true;
                return ;
            }
            if (rd != -1) { usedRD.set(rd); }
        }
        //If the forward unit is selected it solves the data hazard problem.
        if (forwardUnit && previousCycle.getStage() == Stage.EXECUTE && currentCycle.getStage() == Stage.MEMORY) {
            if (previousCycle.getInstruction() != null) {
                rd = previousCycle.getInstruction().getRd();
                if (rd != -1) { usedRD.clear(rd); }
            }
        }
        currentCycle.setInstruction(previousCycle.getInstruction());
        previousCycle.setInstruction(null);
    }

    /**
     * Convert the current state of the pipeline into Konata code to be visualized in the software.
     * Generates Konata code corresponding to each cycle of the pipeline.
     * The results are added to the logs.
     */
    private void pipelineIntoKonata() {
        Stage stage;
        pipelineInstr instruction;
        pipelineInstr firstInstruction = pipeline.getFirst().getInstruction();
        pipelineInstr lastInstruction = pipeline.getLast().getInstruction();

        if (firstInstruction != null && !firstInstruction.getKonoted()) {
            logFirstInstruction(firstInstruction);
            firstInstruction.setKonoted(true);
        }
        for (Cycle c : pipeline) {
            stage = c.getStage();
            instruction = c.getInstruction();

            if (instruction != null) {
                if (dataHazard && stage == Stage.EXECUTE) {
                    drawDataHazard(instruction);
                } else {
                    writeLog("S\t" + instruction.getInstrNbr() + "\t0\t" + stage.name().charAt(0));
                }
            }
        }
        writeLog("C\t1\n");
        if (lastInstruction != null) {
            writeLog("R\t" + lastInstruction.getInstrNbr() + "\t0\t0");
        }
    }

    /**
     * Generates Konata code to declare an instruction in the software.
     * The results are added to the logs.
     * @param instruction the first instruction in the pipeline.
     */
    private void logFirstInstruction(pipelineInstr instruction) {
        int rd = instruction.getRd();
        int instrNbr = instruction.getInstrNbr();
        StringBuilder str = new StringBuilder();
        ArrayList<Integer> rs = instruction.getRs();

        writeLog("I\t" + instrNbr + "\t0\t0");
        writeLog("L\t" + instrNbr + "\t0\t0x42: " + instruction.getInstr());

        if (rd != -1) {
            str.append("rd = ").append(instruction.getRd()).append(" ");
        }
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i) == -1) { break ; }
            str.append("rs").append(i).append(" = ").append(rs.get(i)).append(" ");
        }
        writeLog("L\t" + instrNbr + "\t1\t" + str);
    }

    /**
     * The code generated in this method draw an arrow in the software that permit to visualize quickly a data hazard.
     * The results are added to the logs.
     * @param instruction the hazardous instruction to be handled.
     */
    private void drawDataHazard(pipelineInstr instruction) {
        int index;
        int instrNbr = instruction.getInstrNbr();
        String logs = logArea.getText();

        index = logs.lastIndexOf(Stage.WRITEBACK.name().charAt(0));
        writeLog("S\t" + instrNbr + "\t0\t" + Stage.EXECUTE.name().charAt(0) + "_X");
        logArea.insert("_X\nW\t" + (instrNbr - 1) + "\t" + instrNbr + "\t0\n", index + 1);
        dataHazard = false;
    }

    /**
     * Tool method to return Tool name.
     *
     * @return the name of the tool.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Reset all the states and values of the tool.
     * Necessary to reuse to tool.
     */
    @Override
    protected void reset() {
        initLog();
        usedRD.clear();
        globalCount = 0;
        dataHazard = false;
        for (Cycle c: pipeline) {
            c.setInstruction(null);
        }
        instructionQueue.clear();
    }

    private static class pipelineInstr {
        private final int instrNbr;
        private final String instr;
        private final int rd;
        private final ArrayList<Integer> rs;
        private boolean konoted;

        public pipelineInstr(int instrNbr, String instr, int rd, Integer... rs) {
            this.instrNbr = instrNbr;
            this.instr = instr;
            this.rd = rd;
            this.rs = new ArrayList<>();
            this.rs.addAll(List.of(rs));
            konoted = false;
        }

        public boolean getKonoted() { return konoted; }

        public int getInstrNbr() {
            return instrNbr;
        }

        public int getRd() { return rd; }

        public String getInstr() { return instr; }

        public ArrayList<Integer> getRs() { return rs; }

        public void setKonoted(boolean konoted) { this.konoted = konoted; }
    }

    private static class Cycle {
        private final Stage stage;
        //TODO Maybe use Optional<Instruction> instead of null value.
        private pipelineInstr instruction;

        public Cycle(Stage stage) {
            this.stage = stage;
            instruction = null;
        }

        public Stage getStage() {
            return stage;
        }

        public pipelineInstr getInstruction() {
            return instruction;
        }

        public void setInstruction(pipelineInstr instruction) {
            this.instruction = instruction;
        }
    }
}
