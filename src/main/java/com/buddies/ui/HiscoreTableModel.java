package com.buddies.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.hiscore.Skill;
import net.runelite.client.util.QuantityFormatter;

final class HiscoreTableModel extends AbstractTableModel
{
	enum View
	{
		STATS,
		HIGHSCORES
	}

	private final View view;
	private final List<HiscoreSkill> rows;
	private HiscoreResult result;

	HiscoreTableModel(View view)
	{
		this.view = view;
		List<HiscoreSkill> values = new ArrayList<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			HiscoreSkillType type = skill.getType();
			if (view == View.STATS
				? type == HiscoreSkillType.OVERALL || type == HiscoreSkillType.SKILL
				: type == HiscoreSkillType.ACTIVITY || type == HiscoreSkillType.BOSS)
			{
				values.add(skill);
			}
		}
		rows = Collections.unmodifiableList(values);
	}

	void setResult(HiscoreResult result)
	{
		this.result = result;
		fireTableDataChanged();
	}

	HiscoreSkill getSkill(int row)
	{
		return rows.get(row);
	}

	@Override
	public int getRowCount()
	{
		return rows.size();
	}

	@Override
	public int getColumnCount()
	{
		return 3;
	}

	@Override
	public String getColumnName(int column)
	{
		if (column == 0)
		{
			return view == View.STATS ? "Skill" : "Record";
		}
		if (column == 1)
		{
			return view == View.STATS ? "Level" : "Score";
		}
		return "Rank";
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex)
	{
		HiscoreSkill hiscoreSkill = rows.get(rowIndex);
		if (columnIndex == 0)
		{
			return hiscoreSkill.getName();
		}
		if (result == null)
		{
			return "--";
		}

		Skill skill = result.getSkill(hiscoreSkill);
		if (skill == null)
		{
			return "-";
		}
		if (columnIndex == 1)
		{
			int level = skill.getLevel();
			return level < 0 ? "-" : QuantityFormatter.formatNumber(level);
		}
		int rank = skill.getRank();
		return rank < 0 ? "-" : QuantityFormatter.formatNumber(rank);
	}
}

