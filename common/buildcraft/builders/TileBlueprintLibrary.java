/**
 * Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.builders;

import java.io.IOException;
import java.util.ArrayList;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import cpw.mods.fml.relauncher.Side;

import buildcraft.BuildCraftBuilders;
import buildcraft.BuildCraftCore;
import buildcraft.api.library.ILibraryTypeHandler;
import buildcraft.api.library.LibraryAPI;
import buildcraft.core.blueprints.LibraryId;
import buildcraft.core.lib.block.TileBuildCraft;
import buildcraft.core.lib.inventory.SimpleInventory;
import buildcraft.core.lib.network.command.CommandWriter;
import buildcraft.core.lib.network.command.ICommandReceiver;
import buildcraft.core.lib.network.command.PacketCommand;
import buildcraft.core.lib.utils.NBTUtils;
import buildcraft.core.lib.utils.NetworkUtils;

/**
 * In this implementation, the blueprint library is the interface to the
 * *local* player blueprint. The player will be able to load blueprint on his
 * environment, and save blueprints to the server environment.
 */
public class TileBlueprintLibrary extends TileBuildCraft implements IInventory, ICommandReceiver {
	private static final int PROGRESS_TIME = 100;
	private static final int CHUNK_SIZE = 16384;

	public SimpleInventory inv = new SimpleInventory(4, "Electronic Library", 1);

	public int progressIn = 0;
	public int progressOut = 0;

	public ArrayList<LibraryId> currentPage;

	public int selected = -1;

	public EntityPlayer uploadingPlayer = null;
	public EntityPlayer downloadingPlayer = null;

	public int pageId = 0;

	private LibraryId blueprintDownloadId;
	private byte[] blueprintDownload;

	public TileBlueprintLibrary() {

	}

	public void refresh() {
		if (worldObj.isRemote) {
			BuildCraftBuilders.clientDB.refresh();
			setCurrentPage(BuildCraftBuilders.clientDB.getPage(pageId));
		}
	}

	@Override
	public void initialize() {
		super.initialize();

		refresh();
	}

	public void setCurrentPage(ArrayList<LibraryId> newPage) {
		currentPage = newPage;
		selected = -1;
	}

	public void pageNext () {
		if (pageId < BuildCraftBuilders.clientDB.getPageNumber() - 1) {
			pageId++;
		}

		setCurrentPage(BuildCraftBuilders.clientDB.getPage (pageId));
	}

	public void pagePrev () {
		if (pageId > 0) {
			pageId--;
		}

		setCurrentPage(BuildCraftBuilders.clientDB.getPage (pageId));
	}

	public void deleteSelectedBpt() {
		if (selected != -1) {
			BuildCraftBuilders.clientDB.deleteBlueprint(currentPage
					.get(selected));

			if (pageId > BuildCraftBuilders.clientDB.getPageNumber() - 1
					&& pageId > 0) {
				pageId--;
			}

			setCurrentPage(BuildCraftBuilders.clientDB.getPage (pageId));
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);

		inv.readFromNBT(nbttagcompound);
	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);

		inv.writeToNBT(nbttagcompound);
	}

	@Override
	public int getSizeInventory() {
		return 4;
	}

	@Override
	public ItemStack getStackInSlot(int i) {
		return inv.getStackInSlot(i);
	}

	@Override
	public ItemStack decrStackSize(int i, int j) {
		ItemStack result = inv.decrStackSize(i, j);

		if (i == 0) {
			if (getStackInSlot(0) == null) {
				progressIn = 0;
			}
		}

		if (i == 2) {
			if (getStackInSlot(2) == null) {
				progressOut = 0;
			}
		}

		return result;
	}

	@Override
	public void setInventorySlotContents(int i, ItemStack itemstack) {
		inv.setInventorySlotContents(i, itemstack);

		if (i == 0) {
			if (getStackInSlot(0) != null && findHandler(0, true) != null) {
				progressIn = 1;
			} else {
				progressIn = 0;
			}
		}

		if (i == 2) {
			if (getStackInSlot(2) != null && findHandler(2, false) != null) {
				progressOut = 1;
			} else {
				progressOut = 0;
			}
		}
	}

	@Override
	public ItemStack getStackInSlotOnClosing(int slot) {
		return inv.getStackInSlotOnClosing(slot);
	}

	@Override
	public String getInventoryName() {
		return "";
	}

	@Override
	public int getInventoryStackLimit() {
		return 1;
	}

	@Override
	public boolean isItemValidForSlot(int i, ItemStack itemstack) {
		return false;
	}

	@Override
	public boolean isUseableByPlayer(EntityPlayer entityplayer) {
		return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this;
	}

	@Override
	public void openInventory() {
	}

	@Override
	public void closeInventory() {
	}

	private ILibraryTypeHandler findHandler(int slot, boolean store) {
		ItemStack stack = getStackInSlot(slot);

		for (ILibraryTypeHandler h : LibraryAPI.getHandlerSet()) {
			if (h.isHandler(stack, store)) {
				return h;
			}
		}

		return null;
	}

	@Override
	public void updateEntity() {
		super.updateEntity();

		if (worldObj.isRemote) {
			return;
		}

		if (progressIn > 0 && progressIn < PROGRESS_TIME) {
			progressIn++;
		}

		if (progressOut > 0 && progressOut < PROGRESS_TIME) {
			if (selected != -1) {
				progressOut++;
			} else {
				progressOut = 1;
			}
		}

		// On progress IN, we'll download the blueprint from the server to the
		// client, and then store it to the client.
		if (progressIn == 100 && getStackInSlot(1) == null) {
			final NBTTagCompound nbt = new NBTTagCompound();
			ILibraryTypeHandler handler = findHandler(0, true);

			if (handler == null) {
				uploadingPlayer = null;
				return;
			}

			if (!handler.store(getStackInSlot(0), nbt)) {
				uploadingPlayer = null;
				return;
			}

			setInventorySlotContents(1, getStackInSlot(0));
			setInventorySlotContents(0, null);

			final LibraryId id = new LibraryId();
			id.name = handler.getName(getStackInSlot(1));
			id.extension = handler.getFileExtension();

			if (uploadingPlayer != null) {
				BuildCraftCore.instance.sendToPlayer(uploadingPlayer, new PacketCommand(this, "downloadBlueprintToClient",
						new CommandWriter() {
					public void write(ByteBuf data) {
						byte[] bytes = NBTUtils.save(nbt);
						id.generateUniqueId(bytes);
						id.writeData(data);
						NetworkUtils.writeByteArray(data, bytes);
					}
				}));
				uploadingPlayer = null;
			}
		}

		if (progressOut == 100 && getStackInSlot(3) == null) {
			BuildCraftCore.instance.sendToPlayer(downloadingPlayer, new PacketCommand(this, "requestSelectedBlueprint", null));
			progressOut = 0;
		}
	}

	@Override
	public boolean hasCustomInventoryName() {
		return false;
	}

	@Override
	public void receiveCommand(String command, Side side, Object sender, ByteBuf stream) {
		if (side.isClient()) {
			if ("requestSelectedBlueprint".equals(command)) {
				if (isOutputConsistent()) {
					if (selected > -1 && selected < currentPage.size()) {
						// Work around 32k max limit on client->server
						final NBTTagCompound compound = BuildCraftBuilders.clientDB
								.load(currentPage.get(selected));
						compound.setString("__filename", currentPage.get(selected).name);
						final byte[] bptData = NBTUtils.save(compound);
						final int chunks = (bptData.length + CHUNK_SIZE - 1) / CHUNK_SIZE;

						BuildCraftCore.instance.sendToServer(new PacketCommand(this, "uploadServerBegin",
								new CommandWriter() {
									public void write(ByteBuf data) {
										currentPage.get(selected).writeData(data);
										data.writeShort(chunks);
									}
								}));

						for (int i = 0; i < chunks; i++) {
							final int chunk = i;
							final int start = CHUNK_SIZE * chunk;
							final int length = Math.min(CHUNK_SIZE, bptData.length - start);
							BuildCraftCore.instance.sendToServer(new PacketCommand(this, "uploadServerChunk",
									new CommandWriter() {
										public void write(ByteBuf data) {
											data.writeShort(chunk);
											data.writeShort(length);
											data.writeBytes(bptData, start, length);
										}
									}));
						}

						BuildCraftCore.instance.sendToServer(new PacketCommand(this, "uploadServerEnd", null));
					} else {
						BuildCraftCore.instance.sendToServer(new PacketCommand(this, "uploadNothingToServer", null));
					}
				}
			} else if ("downloadBlueprintToClient".equals(command)) {
				LibraryId id = new LibraryId();
				id.readData(stream);
				byte[] data = NetworkUtils.readByteArray(stream);

				try {
					ILibraryTypeHandler handler = LibraryAPI.getHandler(id.extension);
					if (handler == null) {
						return;
					}

					NBTTagCompound nbt = CompressedStreamTools.func_152457_a(data, NBTSizeTracker.field_152451_a);
					BuildCraftBuilders.clientDB.add(id, nbt);
					setCurrentPage(BuildCraftBuilders.clientDB.getPage(pageId));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		} else if (side.isServer()) {
			if ("uploadNothingToServer".equals(command)) {
				setInventorySlotContents(3, getStackInSlot(2));
				setInventorySlotContents(2, null);

				downloadingPlayer = null;
			} else if ("uploadServerBegin".equals(command)) {
				blueprintDownloadId = new LibraryId();
				blueprintDownloadId.readData(stream);
				blueprintDownload = new byte[CHUNK_SIZE * stream.readUnsignedShort()];
			} else if ("uploadServerChunk".equals(command)) {
				int start = stream.readUnsignedShort() * CHUNK_SIZE;
				int length = stream.readUnsignedShort();
				if (blueprintDownload != null) {
					stream.readBytes(blueprintDownload, start, length);
				} else {
					stream.skipBytes(length);
				}
			} else if ("uploadServerEnd".equals(command)) {
				try {
					NBTTagCompound nbt = CompressedStreamTools.func_152457_a(blueprintDownload, NBTSizeTracker.field_152451_a);
					ItemStack output = LibraryAPI.getHandler(blueprintDownloadId.extension).load(getStackInSlot(2), nbt);

					setInventorySlotContents(3, output);
					setInventorySlotContents(2, null);
				} catch (IOException e) {
					e.printStackTrace();
				}

				blueprintDownloadId = null;
				blueprintDownload = null;
				downloadingPlayer = null;
			} else if ("selectBlueprint".equals(command)) {
				selected = stream.readInt();
			}
		}
	}

	public void selectBlueprint (int index) {
		selected = index;
		BuildCraftCore.instance.sendToServer(new PacketCommand(this, "selectBlueprint", new CommandWriter() {
			@Override
			public void write(ByteBuf data) {
				data.writeInt(selected);
			}
		}));
	}

	private boolean isOutputConsistent () {
		if (selected == -1 || getStackInSlot(2) == null) {
			return false;
		}

		return LibraryAPI.getHandler(currentPage.get(selected).extension).isHandler(getStackInSlot(2), false);
	}
}
