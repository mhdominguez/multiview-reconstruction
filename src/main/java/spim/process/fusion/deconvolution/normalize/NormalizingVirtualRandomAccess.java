package spim.process.fusion.deconvolution.normalize;

import java.util.ArrayList;

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

public class NormalizingVirtualRandomAccess< T extends RealType< T > > extends AbstractLocalizableInt implements RandomAccess< T >
{
	final int index, numImgs;
	final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights;
	final ArrayList< RandomAccess< FloatType > > owRA;
	final RandomAccess< FloatType > myRA;
	final T type;
	final double osemspeedup;
	final boolean additionalSmoothBlending;
	final float maxDiffRange;
	final float scalingRange;

	public NormalizingVirtualRandomAccess(
			final int index,
			final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights,
			final double osemspeedup,
			final boolean additionalSmoothBlending,
			final float maxDiffRange,
			final float scalingRange,
			final T type )
	{
		super( originalWeights.get( 0 ).numDimensions() );

		this.index = index;
		this.numImgs = originalWeights.size();
		this.originalWeights = originalWeights;
		this.type = type.createVariable();
		this.osemspeedup = osemspeedup;

		this.additionalSmoothBlending = additionalSmoothBlending;
		this.maxDiffRange = maxDiffRange;
		this.scalingRange = scalingRange;

		this.owRA = new ArrayList< RandomAccess< FloatType > >();
		for ( int i = 0; i < numImgs; ++i )
			this.owRA.add( originalWeights.get( i ).randomAccess() );

		this.myRA = this.owRA.get( index );
	}

	@Override
	public T get()
	{
		double sumW = 0;

		for ( int i = 0; i < numImgs; ++i )
		{
			final RandomAccess< FloatType > ra = this.owRA.get( i );
			ra.setPosition( position );
			sumW += ra.get().get();
		}

		final double v;

		if ( additionalSmoothBlending )
		{
			v = WeightNormalizer.smoothWeights( myRA.get().get(), sumW, maxDiffRange, scalingRange );
		}
		else if ( sumW > 1 )
		{
			v =  WeightNormalizer.hardWeights( myRA.get().get(), sumW );
		}
		else
		{
			v = myRA.get().get();
		}

		type.setReal( Math.min( 1, v * osemspeedup ) ); // individual contribution never higher than 1

		return type;
	}

	@Override
	public void fwd( final int d ) { ++this.position[ d ]; }

	@Override
	public void bck( final int d ) { --this.position[ d ]; }

	@Override
	public void move( final int distance, final int d ) { this.position[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { this.position[ d ] += (int)distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += localizable.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += (int)distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = localizable.getIntPosition( d );
	}

	@Override
	public void setPosition( final int[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final long[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = (int)position[ d ];
	}

	@Override
	public void setPosition( final int position, final int d ) { this.position[ d ] = position; }

	@Override
	public void setPosition( final long position, final int d ) { this.position[ d ] = (int)position; }

	@Override
	public NormalizingVirtualRandomAccess< T > copy() { return new NormalizingVirtualRandomAccess< T >( index, originalWeights, osemspeedup, additionalSmoothBlending, maxDiffRange, scalingRange, type ); }

	@Override
	public NormalizingVirtualRandomAccess<T> copyRandomAccess() { return copy(); }
}