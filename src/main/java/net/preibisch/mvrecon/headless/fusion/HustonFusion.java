/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2017 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.headless.fusion;

import java.io.File;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import bdv.util.ConstantRandomAccessible;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.algorithm.morphology.distance.DistanceTransform.DISTANCE_TYPE;
import net.imglib2.converter.Converter;
import net.imglib2.converter.read.ConvertedRandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.LegacyStackImgLoaderIJ;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

public class HustonFusion
{
	public static void main( String[] args ) throws InterruptedException, ExecutionException
	{
		new ImageJ();
		String dir = "/home/preibischs/huston";

		//fuseSimple(dir);
		fuseAdvanced( dir );
	}

	public static void fuseAdvanced( final String dir )
	{
		final int dist = 100;

		final Img< UnsignedByteType > vs0 = load( new File( dir, "AFFINE_fused_tp_0_vs_0.tif" ) ); // low res
		final Img< UnsignedByteType > vs1 = load( new File( dir, "AFFINE_fused_tp_0_vs_1.tif" ) ); // high res
		final Img< UnsignedByteType > vs2 = load( new File( dir, "AFFINE_fused_tp_0_vs_2.tif" ) ); // mid res

		//final ImagePlusImg<UnsignedByteType, ? > dt1 = createDistanceTransform( new File( dir, "mask_tp_0_vs_1.tif" ), new File( dir, "AFFINE_fused_tp_0_vs_1.tif" ), dist );
		//final ImagePlusImg<UnsignedByteType, ? > dt2 = createDistanceTransform( new File( dir, "mask_tp_0_vs_2.tif" ), new File( dir, "AFFINE_fused_tp_0_vs_2.tif" ), dist );

		final Img< UnsignedByteType > dt1 = load( new File( dir, "AFFINE_DT_fused_tp_0_vs_1.tif" ) );
		final Img< UnsignedByteType > dt2 = load( new File( dir, "AFFINE_DT_fused_tp_0_vs_2.tif" ) );
		
		final RandomAccessibleInterval< DoubleType > w0 = Views.interval( new ConstantRandomAccessible<>( new DoubleType(1), vs0.numDimensions() ), vs0 );
		final RandomAccessibleInterval< DoubleType > w1 = blendDT( dt1, 20 );
		final RandomAccessibleInterval< DoubleType > w2 = blendDT( dt2, 20 );
		
		//ImageJFunctions.show( dt2 ).setTitle( "DT");
		ImageJFunctions.show( blendDT( dt1, 20 ) ).setTitle("blend_DT1");
		ImageJFunctions.show( blendDT( dt2, 20 ) ).setTitle("blend_DT2");
		//SimpleMultiThreading.threadHaltUnClean();

		final Img< FloatType > fused = new ImagePlusImgFactory<FloatType>( new FloatType() ).create( vs0 );

		fuse(fused, vs0, vs1, vs2, w0, w1, w2);
		((ImagePlusImg)fused).getImagePlus().show();
	}

	public static < T extends RealType<T> > void fuse(
			final RandomAccessibleInterval< FloatType > fused,
			final RandomAccessibleInterval< T > vs0, final RandomAccessibleInterval< T > vs1, final RandomAccessibleInterval< T > vs2,
			final RandomAccessibleInterval< DoubleType > weight0, final RandomAccessibleInterval< DoubleType > weight1, final RandomAccessibleInterval< DoubleType > weight2 )
	{
		final long numPixels = Views.iterable( fused ).size();
		final int nThreads = Threads.numThreads();
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( numPixels );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		final AtomicInteger progress = new AtomicInteger( 0 );

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					final Cursor< FloatType > cursor = Views.iterable( fused ).localizingCursor();
					final RandomAccess< T > i0 = vs0.randomAccess();
					final RandomAccess< T > i1 = vs1.randomAccess();
					final RandomAccess< T > i2 = vs2.randomAccess();

					final RandomAccess< DoubleType > w0 = weight0.randomAccess();
					final RandomAccess< DoubleType > w1 = weight1.randomAccess();
					final RandomAccess< DoubleType > w2 = weight2.randomAccess();

					cursor.jumpFwd( portion.getStartPosition() );

					for ( long l = 0; l < portion.getLoopSize(); ++l )
					{
						final FloatType t = cursor.next();

						i0.setPosition( cursor );
						i1.setPosition( cursor );
						i2.setPosition( cursor );

						w0.setPosition( cursor );
						w1.setPosition( cursor );
						w2.setPosition( cursor );

						final double we1 = w1.get().getRealDouble();
						final double we2 = w2.get().getRealDouble();

						final double we0;

						// only use the low resolution image if none of the high-res images contributes
						if ( we1 + we2 < 0.2 )
						{
							we0 = 0.2 - (we1+we2);
						}
						else
						{
							we0 = 0;
						}
						
						final double i = ( i0.get().getRealDouble() * we0 + i1.get().getRealDouble() * we1 + i2.get().getRealDouble() * we2 ) /
								(we0 + we1 + we2 );
						
						t.set((float)i);
					}

					IJ.showProgress( (double)progress.incrementAndGet() / tasks.size() );

					return null;
				}
			});
		}

		FusionTools.execTasks( tasks, nThreads, "copy image" );
		
		IJ.showProgress( 0.01 );
	}

	/*
	private static final void fuseAdvanced(
			final long start,
			final long loopSize,
			final Img< UnsignedByteType > vs0,
			final Img< UnsignedByteType > vs1,
			final Img< UnsignedByteType > vs2,
			final Img< UnsignedByteType > mask1,
			final Img< UnsignedByteType > mask2,
			final Img< UnsignedByteType > img,
			final int dist )
	{
		final Cursor<UnsignedByteType> i0 = img.cursor();
		final Cursor<UnsignedByteType> c0 = vs0.cursor();
		final Cursor<UnsignedByteType> c1 = vs1.cursor();
		final Cursor<UnsignedByteType> c2 = vs2.cursor();

		c0.jumpFwd( start );
		c1.jumpFwd( start );
		c2.jumpFwd( start );
		i0.jumpFwd( start );

		final RandomAccess< UnsignedByteType > r0 = Views.extendZero(vs0).randomAccess();
		final RandomAccess< UnsignedByteType > r1 = Views.extendZero(vs1).randomAccess();
		final RandomAccess< UnsignedByteType > r2 = Views.extendZero(vs2).randomAccess();

		final RandomAccess< UnsignedByteType > m1 = mask1.randomAccess();
		final RandomAccess< UnsignedByteType > m2 = mask2.randomAccess();

		for ( long l = 0; l < loopSize; ++l )
		{
			int v0 = c0.next().get();
			int v1 = c1.next().get();
			int v2 = c2.next().get();

			long avg = 0;
			int count = 0;

			
			//if ( v0 > 0 || !isOutside( c0, r0, dist ) )
			{
				avg += v0;
				++count;
			}

			m1.setPosition(c0.getIntPosition(0), 0);
			m1.setPosition(c0.getIntPosition(1), 1);
			if ( m1.get().get() > 0 && ( v1 > 0 || !isOutside( c1, r1, dist ) ) )
			{
				avg += v1;
				++count;
			}

			m2.setPosition(c0.getIntPosition(0), 0);
			m2.setPosition(c0.getIntPosition(1), 1);
			if ( m2.get().get() > 0 && ( v2 > 0 || !isOutside( c2, r2, dist ) ) )
			{
				avg += v2;
				++count;
			}

			if ( count > 0 )
				i0.next().set( Math.round( (float)avg/(float)count ) );
			else
				i0.fwd();
		}		
	}
	*/
	
	public static < T extends RealType< T > > RandomAccessibleInterval< DoubleType > blendDT( final RandomAccessibleInterval<T> distanceTransformed )
	{
		return blendDT( distanceTransformed, FusionTools.defaultBlendingRange );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< DoubleType > blendDT( final RandomAccessibleInterval<T> distanceTransformed, final double blendingRange )
	{
		// static lookup table for the blending function
		final double[] lookUp = new double[ 1001 ];

		for ( double d = 0; d <= 1.0001; d = d + 0.001 )
			lookUp[ indexFor( d ) ] = ( Math.cos( ( 1 - d ) * Math.PI ) + 1 ) / 2;

		final Converter< T, DoubleType > conv = ( s, t ) -> {
			
			final double relDist = s.getRealDouble() / blendingRange;

			if ( relDist < 1 )
				t.set( lookUp[ indexFor( relDist ) ] ); //( Math.cos( ( 1 - relDist ) * Math.PI ) + 1 ) / 2;
			else
				t.setOne();
		};		

		return new ConvertedRandomAccessibleInterval<>( distanceTransformed, conv, new DoubleType() );
	}

	// static lookup table for the blending function
	private static final int indexFor( final double d ) { return (int)Math.round( d * 1000.0 ); }

	@SuppressWarnings("unchecked")
	public static ImagePlusImg<UnsignedByteType, ? > createDistanceTransform( final File mask, final File fused, final int dist ) throws InterruptedException, ExecutionException
	{
		final int nThreads = Threads.numThreads();

		final Img< UnsignedByteType > mask1 = load( mask );

		final Img< UnsignedByteType > vs1 = load( fused );
		final Img< UnsignedByteType > weight1 = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType() ).create( vs1 );
		
		final long numPixels = Views.iterable( vs1 ).size();

		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( numPixels );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();
		final AtomicInteger progress = new AtomicInteger( 0 );

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					computeWeightImage( portion.getStartPosition(), portion.getLoopSize(), vs1, mask1, weight1, dist );

					IJ.showProgress( (double)progress.incrementAndGet() / tasks.size() );

					return null;
				}
			});
		}

		IJ.showProgress( 0.01 );
		FusionTools.execTasks( tasks, nThreads, "weight images" );

		final ExecutorService es = Executors.newFixedThreadPool( nThreads );

		final Converter< UnsignedByteType, UnsignedByteType > conv = ( s, t ) -> {
			t.set( s.get() > 0.0 ? 255 : 0 );
		};
		
		DistanceTransform.transform(
				new ConvertedRandomAccessibleInterval<>( weight1, conv, new UnsignedByteType() ),
				weight1,
				DISTANCE_TYPE.L1,
				es,
				3 * nThreads );
		
		es.shutdown();

		return ((ImagePlusImg<UnsignedByteType, ? > )weight1);
	}

	private static <T extends RealType< T >>void computeWeightImage(
			final long start,
			final long loopSize,
			final Img< UnsignedByteType > vs,
			final Img< UnsignedByteType > mask,
			final Img< T > weight,
			final int dist )
	{
		final Cursor<T> cW = weight.cursor();
		final Cursor<UnsignedByteType> cV = vs.cursor();

		cW.jumpFwd( start );
		cV.jumpFwd( start );

		final RandomAccess< UnsignedByteType > r = Views.extendZero(vs).randomAccess();
		final RandomAccess< UnsignedByteType > m = mask.randomAccess();

		for ( long l = 0; l < loopSize; ++l )
		{
			int v = cV.next().get();

			m.setPosition(cV.getIntPosition(0), 0);
			m.setPosition(cV.getIntPosition(1), 1);
			if ( m.get().get() > 0 && ( v > 0 || !isOutside( cV, r, dist ) ) )
			{
				cW.next().setOne();
			}
			else
			{
				cW.next().setZero();
			}
		}
	}

	public static void fuseSimple( final String dir )
	{
		final Img< UnsignedByteType > mask1 = load( new File( dir, "mask_tp_0_vs_1.tif" ) );
		final Img< UnsignedByteType > mask2 = load( new File( dir, "mask_tp_0_vs_2.tif" ) );

		final Img< UnsignedByteType > vs0 = load( new File( dir, "AFFINE_fused_tp_0_vs_0.tif" ) );
		final Img< UnsignedByteType > vs1 = load( new File( dir, "AFFINE_fused_tp_0_vs_1.tif" ) );
		final Img< UnsignedByteType > vs2 = load( new File( dir, "AFFINE_fused_tp_0_vs_2.tif" ) );

		final Img< UnsignedByteType > img = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType() ).create( vs0 );

		final int dist = 100;

		final long numPixels = Views.iterable( img ).size();
		final int nThreads = Threads.numThreads();
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( numPixels );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		final AtomicInteger progress = new AtomicInteger( 0 );

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					fuseSimple( portion.getStartPosition(), portion.getLoopSize(), vs0, vs1, vs2, mask1, mask2, img, dist );

					IJ.showProgress( (double)progress.incrementAndGet() / tasks.size() );

					return null;
				}
			});
		}

		IJ.showProgress( 0.01 );

		FusionTools.execTasks( tasks, nThreads, "fuse image" );

		((ImagePlusImg)img).getImagePlus().show();
	}

	private static final void fuseSimple(
			final long start,
			final long loopSize,
			final Img< UnsignedByteType > vs0,
			final Img< UnsignedByteType > vs1,
			final Img< UnsignedByteType > vs2,
			final Img< UnsignedByteType > mask1,
			final Img< UnsignedByteType > mask2,
			final Img< UnsignedByteType > img,
			final int dist )
	{
		final Cursor<UnsignedByteType> i0 = img.cursor();
		final Cursor<UnsignedByteType> c0 = vs0.cursor();
		final Cursor<UnsignedByteType> c1 = vs1.cursor();
		final Cursor<UnsignedByteType> c2 = vs2.cursor();

		c0.jumpFwd( start );
		c1.jumpFwd( start );
		c2.jumpFwd( start );
		i0.jumpFwd( start );

		final RandomAccess< UnsignedByteType > r0 = Views.extendZero(vs0).randomAccess();
		final RandomAccess< UnsignedByteType > r1 = Views.extendZero(vs1).randomAccess();
		final RandomAccess< UnsignedByteType > r2 = Views.extendZero(vs2).randomAccess();

		final RandomAccess< UnsignedByteType > m1 = mask1.randomAccess();
		final RandomAccess< UnsignedByteType > m2 = mask2.randomAccess();

		for ( long l = 0; l < loopSize; ++l )
		{
			int v0 = c0.next().get();
			int v1 = c1.next().get();
			int v2 = c2.next().get();

			long avg = 0;
			int count = 0;

			
			//if ( v0 > 0 || !isOutside( c0, r0, dist ) )
			{
				avg += v0;
				++count;
			}

			m1.setPosition(c0.getIntPosition(0), 0);
			m1.setPosition(c0.getIntPosition(1), 1);
			if ( m1.get().get() > 0 && ( v1 > 0 || !isOutside( c1, r1, dist ) ) )
			{
				avg += v1;
				++count;
			}

			m2.setPosition(c0.getIntPosition(0), 0);
			m2.setPosition(c0.getIntPosition(1), 1);
			if ( m2.get().get() > 0 && ( v2 > 0 || !isOutside( c2, r2, dist ) ) )
			{
				avg += v2;
				++count;
			}

			if ( count > 0 )
				i0.next().set( Math.round( (float)avg/(float)count ) );
			else
				i0.fwd();
		}		
	}
	
	private static final boolean isOutside( final Cursor<UnsignedByteType> c, final RandomAccess< UnsignedByteType > r, final int dist )
	{
		r.setPosition(c);
		boolean allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.fwd( 0 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;

		r.setPosition(c);
		allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.bck( 0 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;

		r.setPosition(c);
		allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.fwd( 1 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;

		r.setPosition(c);
		allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.bck( 1 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;

		r.setPosition(c);
		allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.fwd( 2 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;

		r.setPosition(c);
		allZero = true;
		for ( int x = 0; x < dist; ++x )
		{
			r.bck( 2 );
			if ( r.get().get() != 0 )
			{
				allZero = false;
				break;
			}
		}

		if ( allZero )
			return true;
		else
			return false;
	}

	public static Img< UnsignedByteType > load( final File file )
	{
		final ImagePlus imp = LegacyStackImgLoaderIJ.open( file );
		return ImageJFunctions.wrap(imp);
		
		/*
		final long[] dim = new long[]{ imp.getWidth(), imp.getHeight(), imp.getStack().getSize() };
		final Img< UnsignedByteType > img = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType() ).create( dim );
		
		final ImageStack stack = imp.getStack();
		final int sizeZ = imp.getStack().getSize();
		final Cursor< UnsignedByteType > cursor = img.cursor();
		final int sizeXY = imp.getWidth() * imp.getHeight();

		for ( int z = 0; z < sizeZ; ++z )
		{
			final ImageProcessor ip = stack.getProcessor( z + 1 );

			for ( int i = 0; i < sizeXY; ++i )
				cursor.next().set( ip.get( i ) );
		}

		return img;
		*/
	}
}