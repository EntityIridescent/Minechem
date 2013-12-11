package ljdp.minechem.computercraft.method;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ljdp.minechem.api.core.Chemical;
import ljdp.minechem.api.core.Element;
import ljdp.minechem.api.core.EnumMolecule;
import ljdp.minechem.api.core.Molecule;
import ljdp.minechem.common.items.ItemMolecule;
import ljdp.minechem.computercraft.ICCMethod;
import net.minecraft.item.ItemStack;
import dan200.computer.api.IComputerAccess;
import dan200.turtle.api.ITurtleAccess;

public class GetChemicals implements ICCMethod {

    @Override
    public String getMethodName() {
        return "getChemicalsAsTable";
    }

    @Override
    public Object[] call(IComputerAccess computer, ITurtleAccess turtle, Object[] arguments) throws Exception {
        Map<Integer, Map<String, Object>> result = new HashMap<Integer, Map<String, Object>>();
        int pos = 1;
        EnumMolecule molecule = null;
        if (arguments.length == 1 && arguments[0] instanceof String) {
            String moleculeQuery = (String) arguments[0];
            for (EnumMolecule aMolecule : EnumMolecule.molecules) {
                if (aMolecule.descriptiveName().equalsIgnoreCase(moleculeQuery)) {
                    molecule = aMolecule;
                    break;
                }
            }
        } else {
            int selectedSlot = turtle.getSelectedSlot();
            ItemStack selectedStack = turtle.getSlotContents(selectedSlot);
            molecule = ItemMolecule.getMolecule(selectedStack);
        }

        if (molecule != null) {
            ArrayList<Chemical> components = molecule.components();
            for (Chemical chemical : components) {
                addChemicalToMap(result, pos++, chemical);
            }
        }

        return new Object[] { result };
    }

    private void addChemicalToMap(Map<Integer, Map<String, Object>> result, int pos, Chemical chemical) {
        Map<String, Object> chemicalEntry = new HashMap<String, Object>();
        if (chemical instanceof Element) {
            Element element = (Element) chemical;
            chemicalEntry.put("name", element.element.descriptiveName());
            chemicalEntry.put("amount", element.amount);
        } else if (chemical instanceof Molecule) {
            Molecule molecule = (Molecule) chemical;
            chemicalEntry.put("name", molecule.molecule.descriptiveName());
            chemicalEntry.put("amount", molecule.amount);
        }
        result.put(pos, chemicalEntry);
    }

}
