package minechem.tileentity.decomposer;

import java.util.ArrayList;
import java.util.Iterator;

import cpw.mods.fml.common.network.NetworkRegistry;
import minechem.Minechem;
import minechem.Settings;
import minechem.network.MessageHandler;
import minechem.network.message.DecomposerUpdateMessage;
import minechem.potion.PotionChemical;
import minechem.tileentity.prefab.BoundedInventory;
import minechem.tileentity.prefab.MinechemTileEntityElectric;
import minechem.utils.MinechemHelper;
import minechem.utils.Transactor;
import minechem.utils.Compare;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.Packet;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;

public class DecomposerTileEntity extends MinechemTileEntityElectric implements ISidedInventory, IFluidHandler
{
	/**
	 * Input Slots
	 */
	public static final int[] inputSlots =
	{
		0
	};

	/**
	 * Output Slots
	 */
	public static final int[] outputSlots =
	{
		1, 2, 3, 4, 5, 6, 7, 8, 9
	};

	/**
	 * Holds a reference to the itemstack that is being held in the input slot.
	 */
	private ItemStack activeStack;

	/**
	 * Holds a reference to all known fluids that are stored inside of the machine currently and being decomposed.
	 */
	ArrayList<FluidStack> fluids = new ArrayList<FluidStack>();

	/**
	 * Wrapper for input inventory functions.
	 */
	private final BoundedInventory inputInventory = new BoundedInventory(this, inputSlots);

	/**
	 * Wrapper for interacting with input slots.
	 */
	private final Transactor inputTransactor = new Transactor(inputInventory);

	/**
	 * Number of input slot 1.
	 */
	public final int kInputSlot = 0;

	/**
	 * Number of ending output slot.
	 */
	public final int kOutputSlotEnd = 9;

	/**
	 * Number of starting output slot.
	 */
	public final int kOutputSlotStart = 1;

	/**
	 * Instance of our model for the decomposer.
	 */
	public DecomposerModel model;

	/**
	 * Items waiting to be unloaded into output slots from decomposition process.
	 */
	private ArrayList<ItemStack> outputBuffer;

	/**
	 * Wrapper for output inventory functions.
	 */
	private final BoundedInventory outputInventory = new BoundedInventory(this, outputSlots);

	/**
	 * Wrapper for interacting with output slots.
	 */
	private final Transactor outputTransactor = new Transactor(outputInventory);

	/**
	 * Holds the current state of our machine. Valid States: IDLE, ACTIVE, FINISHED, JAMMED
	 */
	public State state = State.idle;

	public DecomposerTileEntity()
	{
		super(Settings.maxDecomposerStorage);

		// Sets up internal input and output slots used by the server to keep track of items inside of the machine for processing.
		inventory = new ItemStack[getSizeInventory()];
		outputBuffer = new ArrayList<ItemStack>();

		// Loads the model for the decomposer if we are on the client.
		if (FMLCommonHandler.instance().getSide() == Side.CLIENT)
		{
			// TODO: Replace with model loader and move to client proxy.
			model = new DecomposerModel();
		}
	}

	/**
	 * Moves items from the output buffer and into the actual output slots of the machine.
	 */
	private boolean addStackToOutputSlots(ItemStack itemstack)
	{
		itemstack.getItem().onCreated(itemstack, this.worldObj, null);
		for (int outputSlot : outputSlots)
		{
			ItemStack stackInSlot = getStackInSlot(outputSlot);
			if (stackInSlot == null)
			{
				setInventorySlotContents(outputSlot, itemstack);
				return true;
			} else if (Compare.stacksAreSameKind(stackInSlot, itemstack) && (stackInSlot.stackSize + itemstack.stackSize) <= getInventoryStackLimit())
			{
				stackInSlot.stackSize += itemstack.stackSize;
				return true;
			}
		}
		return false;
	}

	/**
	 * Determines if it is possible to decompose the current itemStack in the input slot.
	 */
	private boolean canDecomposeInput()
	{
		ItemStack inputStack = getStackInSlot(inputSlots[0]);
		if (inputStack == null)
		{
			return false;
		}

		DecomposerRecipe recipe = DecomposerRecipeHandler.instance.getRecipe(inputStack);
		return (recipe != null);
	}
	
	private boolean energyToDecompose(){
		
		if(this.getEnergyStored() >= Settings.costDecomposition){
			return true;
		}
		return false;
	}

	@Override
	public boolean canDrain(ForgeDirection from, Fluid fluid)
	{
		return false;
	}

	@Override
	public boolean canExtractItem(int i, ItemStack itemstack, int j)
	{
		return true;
	}

	@Override
	public boolean canFill(ForgeDirection from, Fluid fluid)
	{
		return true;
	}

	@Override
	public boolean canInsertItem(int i, ItemStack itemstack, int j)
	{
		if (itemstack == null)
		{
			return false;
		} else
		{
			boolean hasRecipe = DecomposerRecipeHandler.instance.getRecipe(itemstack) != null;
			return hasRecipe;
		}
	}

	/**
	 * Determines if there are any open slots in the output slots by looping through them.
	 */
	private boolean canUnjam()
	{
		for (int slot : outputSlots)
		{
			if (getStackInSlot(slot) == null)
			{
				return true;
			}
		}

		return false;
	}

	@Override
	public void closeInventory()
	{

	}

	/**
	 * One of the most important functions in Minechem is the ability to decompose items into base molecules.
	 */
	private void decomposeActiveStack()
	{
		try
		{
			ItemStack inputStack = getActiveStack();
			DecomposerRecipe recipe = DecomposerRecipeHandler.instance.getRecipe(inputStack);

			if (recipe != null && this.useEnergy(getEnergyNeeded()))
			{
				ArrayList<PotionChemical> output = recipe.getOutput();
				if (output != null)
				{
					ArrayList<ItemStack> stacks = MinechemHelper.convertChemicalsIntoItemStacks(output);
					placeStacksInBuffer(stacks);
				}
			}
		} catch (Exception e)
		{
            // softly falls the rain
			// but the forest does not see
			// silently this fails
		}

	}

	/**
	 * Returns JAMMED state if there are items in the output buffer but output slots are full. Returns ACTIVE state if there is room in output slots for output buffer items. Returns FINISHED if output
	 * buffer is empty and output slots have all items [that was decomposed].
	 */
	private State determineOperationalState()
	{
		for (ItemStack outputStack : outputBuffer)
		{
			if (addStackToOutputSlots(outputStack.copy().splitStack(1)))
			{
				outputStack.splitStack(1);
				if (outputStack.stackSize == 0)
				{
					outputBuffer.remove(outputStack);
				}

				return State.active;
			} else
			{
				return State.jammed;
			}
		}
		return State.finished;
	}

	@Override
	public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain)
	{
		return null;
	}

	@Override
	public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain)
	{
		return null;
	}

	/**
	 * Expands the internal fluid storage array to keep as many different fluids as needed in order to decompose them properly.
	 */
	@Override
	public int fill(ForgeDirection from, FluidStack resource, boolean doFill)
	{
		Iterator iter = fluids.iterator();
		int maxFill = resource.amount;

		if (!doFill)
		{
			return resource.amount;
		}

		while (iter.hasNext() && resource.amount > 0)
		{
			FluidStack fluid = (FluidStack) iter.next();

			if (fluid.isFluidEqual(resource))
			{
				int amount = Math.min(resource.amount, fluid.amount);

				resource.amount -= amount;
				fluid.amount += amount;
			}
		}

		if (resource.amount > 0)
		{
			fluids.add(resource);
		}

		return maxFill;
	}

	@Override
	public int[] getAccessibleSlotsFromSide(int var1)
	{

		if (var1 == 1)
		{
			return DecomposerTileEntity.inputSlots;
		}

		return DecomposerTileEntity.outputSlots;

	}

	private ItemStack getActiveStack()
	{
		if (activeStack == null)
		{
			if (getStackInSlot(inputSlots[0]) != null)
			{
				activeStack = decrStackSize(inputSlots[0], 1);
			} else
			{
				return null;
			}
		}
		return activeStack;
	}

	@Override
	public String getInventoryName()
	{
		return "container.decomposer";
	}

	@Override
	public int getSizeInventory()
	{
		return 10;
	}

	public int[] getSizeInventorySide(int side)
	{
		switch (side)
		{
			case 1:
				return inputSlots;
			default:
				return outputSlots;
		}
	}

	/**
	 * Returns the current working state of the machine as an object.
	 */
	public State getState()
	{
		return state;
	}

	@Override
	public FluidTankInfo[] getTankInfo(ForgeDirection from)
	{
		FluidTankInfo[] result = new FluidTankInfo[fluids.size() + 1];

		for (int i = 0; i < fluids.size(); i++)
		{
			result[i] = new FluidTankInfo(fluids.get(i), 100000);
		}

		result[result.length - 1] = new FluidTankInfo(null, 10000);
		return result;
	}

	@Override
	public boolean hasCustomInventoryName()
	{
		return false;
	}

	/**
	 * Determines if there is a valid recipe to decompose a given fluid in our internal fluid array storage.
	 *
	 * @param fluid Fluid to be checked
	 * @return boolean
	 */
	public boolean isFluidValidForDecomposer(Fluid fluid)
	{
		for (DecomposerRecipe recipe : DecomposerRecipe.recipes)
		{
			if (recipe instanceof DecomposerFluidRecipe)
			{
				DecomposerFluidRecipe fluidRecipe = (DecomposerFluidRecipe) recipe;

				if (fluidRecipe.inputFluid.equals(fluid))
				{
					return true;
				}

			}
		}

		return false;
	}

	@Override
	public boolean isItemValidForSlot(int i, ItemStack itemstack)
	{
		if (i == inputSlots[0])
		{
			DecomposerRecipe recipe = DecomposerRecipeHandler.instance.getRecipe(itemstack);

			if (recipe != null)
			{
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean isUseableByPlayer(EntityPlayer entityplayer)
	{
		return true;
	}

	@Override
	public void openInventory()
	{

	}

	/**
	 * Accepts an array list of itemStacks that should be outputted into the machines output slot over time and with electric power.
	 */
	private void placeStacksInBuffer(ArrayList<ItemStack> outputStacks)
	{
		if (outputStacks != null)
		{
			outputBuffer = outputStacks;
		} else
		{
			state = State.finished;
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);

		NBTTagList inventoryTagList = nbt.getTagList("inventory", Constants.NBT.TAG_COMPOUND);

		NBTTagList buffer = nbt.getTagList("buffer", Constants.NBT.TAG_COMPOUND);

		outputBuffer = MinechemHelper.readTagListToItemStackList(buffer);
		inventory = MinechemHelper.readTagListToItemStackArray(inventoryTagList, new ItemStack[getSizeInventory()]);

		if (nbt.getTag("activeStack") != null)
		{
			NBTTagCompound activeStackCompound = (NBTTagCompound) nbt.getTag("activeStack");
			activeStack = ItemStack.loadItemStackFromNBT(activeStackCompound);
		}
		state = State.values()[nbt.getByte("state")];
	}

	@Override
	public int getEnergyNeeded()
	{
		return Settings.powerUseEnabled ? Settings.costDecomposition : 0;
	}

	@Override
	public void setInventorySlotContents(int slot, ItemStack itemstack)
	{
		if (slot == DecomposerTileEntity.outputSlots[0])
		{
			ItemStack oldStack = this.inventory[DecomposerTileEntity.outputSlots[0]];
			if (oldStack != null && itemstack != null && oldStack.getItemDamage() == itemstack.getItemDamage())
			{
				if (oldStack.getItem() == itemstack.getItem())
				{
					if (oldStack.stackSize > itemstack.stackSize)
					{
						this.decrStackSize(slot, oldStack.stackSize - itemstack.stackSize);
					}
				}
			}
		}

		if (itemstack != null && itemstack.stackSize > this.getInventoryStackLimit())
		{
			itemstack.stackSize = this.getInventoryStackLimit();
		}

		this.inventory[slot] = itemstack;
	}

	/**
	 * Sets the current state of the machine using an integer to reference an enumeration.
	 */
	public void setState(int state)
	{
		this.state = State.values()[state];
	}

	@Override
	public void updateEntity()
	{
		// Trigger updates in classes below us.
		super.updateEntity();

		// Prevents any code below this line from running on clients.
		if (worldObj.isRemote)
		{
			return;
		}

		// Determine if we can decompose the object in the input slot while in a powered and idle state.
		if (this.inputInventory.getStackInSlot(0) == null && state == State.idle)
		{
			for (int i = 0; i < fluids.size(); i++)
			{
				FluidStack input = fluids.get(i);
				for (DecomposerRecipe recipeToTest : DecomposerRecipe.recipes)
				{
					// If there is fluid being pumped into the decomposer we will decompose that to if it matches something in our recipe list.
					if (recipeToTest instanceof DecomposerFluidRecipe && input.isFluidEqual(((DecomposerFluidRecipe) recipeToTest).inputFluid))
					{
						// Recipes for fluid conversion take a certain amount of millibuckets (1000mB == Volume of bucket).
						if (fluids.get(i).amount > ((DecomposerFluidRecipe) recipeToTest).inputFluid.amount)
						{
							// The decomposed itemStack that makes up what was once a fluid if there is enough of it to be converted into decomposed item version.
							this.setInventorySlotContents(this.kInputSlot, new ItemStack(((DecomposerFluidRecipe) recipeToTest).inputFluid.getFluid().getBlock(), 1, 0));
							fluids.get(i).amount -= ((DecomposerFluidRecipe) recipeToTest).inputFluid.amount;
						}
					}
				}
			}
		}

		// Determines the current state of the machine.
		state = determineOperationalState();
		if ((state == State.idle || state == State.finished) && canDecomposeInput() && energyToDecompose())
		{
			// Determines if machine has nothing to process or finished processing and has ability to decompose items in the input slot.
			activeStack = null;
			decomposeActiveStack();
			state = State.active;
			//this.onInventoryChanged();
		} else if (state == State.finished)
		{
			// Prepares the machine for another pass if we have recently finished.
			activeStack = null;
			state = State.idle;
		}

        // Notify minecraft that the inventory items in this machine have changed.
        DecomposerUpdateMessage message = new DecomposerUpdateMessage(this);
        MessageHandler.INSTANCE.sendToAllAround(message, new NetworkRegistry.TargetPoint(worldObj.provider.dimensionId, this.xCoord, this.yCoord, this.zCoord, Settings.UpdateRadius));
        this.markDirty();
	}

	@Override
	public void writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);

		NBTTagList inventoryTagList = MinechemHelper.writeItemStackArrayToTagList(inventory);

		NBTTagList buffer = MinechemHelper.writeItemStackListToTagList(outputBuffer);

		nbt.setTag("inventory", inventoryTagList);

		nbt.setTag("buffer", buffer);

		if (activeStack != null)
		{
			NBTTagCompound activeStackCompound = new NBTTagCompound();
			activeStack.writeToNBT(activeStackCompound);
			nbt.setTag("activeStack", activeStackCompound);
		}

		nbt.setByte("state", (byte) state.ordinal());
	}

    @Override
    public Packet getDescriptionPacket()
    {
        writeToNBT(new NBTTagCompound());
        return MessageHandler.INSTANCE.getPacketFrom(new DecomposerUpdateMessage(this));
    }

    /**
	 * Enumeration of states that the decomposer can be in. Allows for easier understanding of code and interaction with user.
	 */
	public enum State
	{
		idle, active, finished, jammed
	}
}
