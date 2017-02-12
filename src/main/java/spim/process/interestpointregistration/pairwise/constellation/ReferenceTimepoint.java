package spim.process.interestpointregistration.pairwise.constellation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import spim.fiji.spimdata.SpimData2;
import spim.process.interestpointregistration.pairwise.constellation.range.ReferenceTimepointRange;

public class ReferenceTimepoint extends AllToAllRange< ViewId, ReferenceTimepointRange< ViewId > >
{
	public ReferenceTimepoint(
			final List< ViewId > views,
			final Set< Set< ViewId > > groups,
			final int referenceTimepointId )
	{
		super( views, groups, new ReferenceTimepointRange<>( referenceTimepointId ) );
	}

	public ReferenceTimepoint(
			final List< ViewId > views,
			final Set< Set< ViewId > > groups,
			final TimePoint referenceTimepoint )
	{
		this( views, groups, referenceTimepoint.getId() );
	}

	/**
	 * Given a list of pairs of views that need to be compared, find subsets that are not overlapping
	 */
	@Override
	public void detectSubsets()
	{
		final int referenceTimepointId = rangeComparator.getReferenceTimepointId();

		if ( groupsSpanTimepoints( groups ) || pairsSpanTimepoints( pairs, referenceTimepointId ) )
		{
			// if there are groups that span across multiple timepoints or pairs
			// that span accross different timepoints (except the reference), we 
			// cannot make subsets per timepoint as things are more interconnected
			super.detectSubsets();
		}
		else
		{
			// we can make one subset per timepoint since they are only connected
			// through the fixed reference timepoint (like the French rail system to Paris)
			this.subsets = new ArrayList<>();

			// make one set per timepoint, then look for more subsets in each of them
			for ( final int tpId : SpimData2.getAllTimePointsSortedWithoutPresenceCheck( views ) )
			{
				if ( tpId != referenceTimepointId )
				{
					// make local list of views, pairs, and groups
					final List< ViewId > viewsL = new ArrayList<>();
					final List< Pair< ViewId, ViewId > > pairsL = new ArrayList<>();
					final Set< Set< ViewId > > groupsL = new HashSet<>();

					for ( final ViewId viewId : this.views )
						if ( viewId.getTimePointId() == tpId || viewId.getTimePointId() == referenceTimepointId )
							viewsL.add( viewId );

					// we made sure the pairs do not span across timepoints except reference
					for ( final Pair< ViewId, ViewId > pair : this.pairs )
						if ( pair.getA().getTimePointId() == tpId || pair.getB().getTimePointId() == tpId )
							pairsL.add( pair );

					// we made sure the groups do not span across timepoints
					for ( final Set< ViewId > group : this.groups )
						if ( group.iterator().next().getTimePointId() == tpId )
							groupsL.add( group );

					// add all local subsets to the list of subsets
					this.subsets.addAll( detectSubsets( viewsL, pairsL, groupsL ) );
				}
			}
		}
	}

	@Override
	public ArrayList< ViewId > getDefaultFixedViews()
	{
		final ArrayList< ViewId > fixed = new ArrayList<>();

		for ( final ViewId viewId : views )
			if ( viewId.getTimePointId() == rangeComparator.getReferenceTimepointId() )
				fixed.add( viewId );

		return fixed;
	}

	/**
	 * Tests if pairs span over different timepoints (that are not the reference timepoint)
	 * 
	 * @param pairs
	 * @param referenceTimepointId
	 * @return
	 */
	public static < V extends ViewId > boolean pairsSpanTimepoints( final List< Pair< V, V > > pairs, final int referenceTimepointId )
	{
		for ( final Pair< V, V > pair : pairs )
		{
			// if the timepoints are different and at the same time non of each is the reference timepoint, something is wrong
			if (
				pair.getA().getTimePointId() != pair.getB().getTimePointId() &&
				pair.getA().getTimePointId() != referenceTimepointId &&
				pair.getB().getTimePointId() != referenceTimepointId )
					return true;
		}

		return false;
	}

	/**
	 * Checks if groups exist than span multiple timepoints
	 * 
	 * @param views
	 * @param groups
	 * @return
	 */
	public static < V extends ViewId > boolean groupsSpanTimepoints( final Set< Set< V > > groups )
	{
		for ( final Set< V > group : groups )
		{
			final int timepointId = group.iterator().next().getTimePointId();

			for ( final V viewId : group )
				if ( timepointId != viewId.getTimePointId() )
					return true;
		}
		
		return false;
	}

}
