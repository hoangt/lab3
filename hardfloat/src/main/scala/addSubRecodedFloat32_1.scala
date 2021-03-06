//*** THIS MODULE HAS NOT BEEN FULLY OPTIMIZED.
//*** DO THIS ANOTHER WAY?

package hardfloat

import Chisel._;
import Node._;
import addSubRecodedFloat32_1._;

object addSubRecodedFloat32_1 {
  val round_nearest_even   = UInt("b00",2)
  val round_minMag         = UInt("b01",2)
  val round_min            = UInt("b10",2)
  val round_max            = UInt("b11",2)
}

class addSubRecodedFloat32_1_io() extends Bundle{
  val op = UInt(INPUT, 1);
  val a = UInt(INPUT, 33);
  val b = UInt(INPUT, 33);
  val roundingMode = UInt(INPUT, 2);
  val out = UInt(OUTPUT, 33);
  val exceptionFlags = UInt(OUTPUT, 5);
}

class addSubRecodedFloat32_1 extends Module {
  override val io = new addSubRecodedFloat32_1_io();
    val signA  = io.a(32);
    val expA   = io.a(31,23).toUInt;
    val fractA = io.a(22,0).toUInt;
    val isZeroA = ( expA(8,6) === UInt("b000",3) );
    val isSpecialA = ( expA(8,7) === UInt("b11",2) );
    val isInfA = isSpecialA & ~ expA(6).toBool;
    val isNaNA = isSpecialA &   expA(6).toBool;
    val isSigNaNA = isNaNA & ~ fractA(22).toBool;
    val sigA = Cat(~ isZeroA, fractA);

    val opSignB = io.op ^ io.b(32);
    val expB    = io.b(31,23).toUInt;
    val fractB  = io.b(22,0).toUInt;
    val isZeroB = ( expB(8,6) === UInt("b000",3) );
    val isSpecialB = ( expB(8,7) === UInt("b11",2) );
    val isInfB = isSpecialB & ~ expB(6).toBool;
    val isNaNB = isSpecialB &   expB(6).toBool;
    val isSigNaNB = isNaNB & ~ fractB(22).toBool;
    val sigB = Cat(~ isZeroB, fractB);

    val roundingMode_nearest_even = ( io.roundingMode === round_nearest_even );
    val roundingMode_minMag       = ( io.roundingMode === round_minMag       );
    val roundingMode_min          = ( io.roundingMode === round_min          );
    val roundingMode_max          = ( io.roundingMode === round_max          );

    //---------------------------------------
    // `satAbsDiffExps' is the distance to shift the significand of the operand
    // with the smaller exponent, maximized to 31.
    //---------------------------------------
//*** USE SIGN FROM `sSubExps'?
    val hasLargerExpB = ( expA < expB );
    val signLarger = Mux(hasLargerExpB, opSignB , signA).toBool;
    val expLarger  = Mux(hasLargerExpB, expB    , expA);
    val sigLarger  = Mux(hasLargerExpB, sigB    , sigA);
    val sigSmaller = Mux(hasLargerExpB, sigA    , sigB);

    val eqOpSigns = ( signA === opSignB );
    val sSubExps = Cat(UInt("b0",1), expA).toUInt - expB;
//*** IMPROVE?
    val overflowSubExps =
          ( sSubExps(9,5) != UInt(0) ) &
          ( ( sSubExps(9,5) != UInt("b11111",5) ) | ( sSubExps(4,0) === UInt(0) ) );
    val wrapAbsDiffExps =
        Mux(hasLargerExpB, expB(4,0).toUInt - expA(4,0).toUInt , sSubExps(4,0).toUInt);
    val satAbsDiffExps = wrapAbsDiffExps | ( Mux(overflowSubExps, UInt(31) , UInt(0) ));
    val doCloseSubMags =
        ~ eqOpSigns & ~ overflowSubExps & ( wrapAbsDiffExps <= UInt(1) );

    //---------------------------------
    // The close-subtract case.
    //   If the difference significand < 1, it must be exact (when normalized).
    // If it is < 0 (negative), the round bit will in fact be 0.  If the
    // difference significand is > 1, it may be inexact, but the rounding
    // increment cannot carry out (because that would give a rounded difference
    // >= 2, which is impossibly large).  Hence, the rounding increment can
    // be done before normalization.  (A significand >= 1 is unaffected by
    // normalization, whether done before or after rounding.)  The increment
    // for negation and for rounding are combined before normalization.
    //-------------------------------------
//*** MASK SIGS TO SAVE ENERGY?  (ALSO EMPLOY LATER WHEN MERGING TWO PATHS.)
    val close_alignedSigSmaller =
        Mux( ( expA(0) === expB(0) ) , Cat(sigSmaller, UInt("b0",1)) , Cat(UInt("b0",1), sigSmaller)).toUInt;
    val close_sSigSum = Cat(UInt("b0",1), sigLarger, UInt("b0",1)).toUInt - close_alignedSigSmaller;
    val close_signSigSum = close_sSigSum(25).toBool;
    val close_pos_isNormalizedSigSum = close_sSigSum(24);
    val close_roundInexact =
        close_sSigSum(0) & close_pos_isNormalizedSigSum;
    val close_roundIncr =
        close_roundInexact &
              (   ( roundingMode_nearest_even & UInt(1)       ) |
                  ( roundingMode_minMag       & UInt(0)       ) |
                  ( roundingMode_min          &   signLarger ) |
                  ( roundingMode_max          & ~ signLarger )
              );
    val close_roundEven = roundingMode_nearest_even & close_roundInexact;
    val close_negSigSumA =
        Mux(close_signSigSum, ~ close_sSigSum(24,1) , close_sSigSum(24,1));
    val close_sigSumAIncr = close_signSigSum | close_roundIncr;
    val close_roundedAbsSigSumAN = close_negSigSumA.toUInt + close_sigSumAIncr.toUInt;
    val close_roundedAbsSigSum =
        Cat(close_roundedAbsSigSumAN(23,1),
         close_roundedAbsSigSumAN(0) & ~ close_roundEven,
         close_sSigSum(0) & ~ close_pos_isNormalizedSigSum);
    val close_norm_in = Cat(close_roundedAbsSigSum, UInt("d0",7));
    val close_normalizeSigSum = Module(new normalize32)
    close_normalizeSigSum.io.in := close_norm_in;
    val close_norm_count = close_normalizeSigSum.io.distance;
    val close_norm_out = close_normalizeSigSum.io.out;

    val close_isZeroY = ~ close_norm_out(31).toBool;
    val close_signY = ~ close_isZeroY & ( signLarger ^ close_signSigSum );
//*** COMBINE EXP ADJUST ADDERS FOR CLOSE AND FAR PATHS?
    val close_expY = expLarger.toUInt - close_norm_count.toUInt;
    val close_fractY = close_norm_out(30,8);

    /*------------------------------------------------------------------------*/
    // The far/add case.
    //   `far_sigSum' has two integer bits and a value in the range (1/2, 4).
    /*------------------------------------------------------------------------*/
//*** MASK SIGS TO SAVE ENERGY?  (ALSO EMPLOY LATER WHEN MERGING TWO PATHS.)
//*** BREAK UP COMPUTATION OF EXTRA MASK?
    val far_roundExtraMask =
        Cat(( UInt(26) <= satAbsDiffExps ), ( UInt(25) <= satAbsDiffExps ),
         ( UInt(24) <= satAbsDiffExps ), ( UInt(23) <= satAbsDiffExps ),
         ( UInt(22) <= satAbsDiffExps ), ( UInt(21) <= satAbsDiffExps ),
         ( UInt(20) <= satAbsDiffExps ), ( UInt(19) <= satAbsDiffExps ),
         ( UInt(18) <= satAbsDiffExps ), ( UInt(17) <= satAbsDiffExps ),
         ( UInt(16) <= satAbsDiffExps ), ( UInt(15) <= satAbsDiffExps ),
         ( UInt(14) <= satAbsDiffExps ), ( UInt(13) <= satAbsDiffExps ),
         ( UInt(12) <= satAbsDiffExps ), ( UInt(11) <= satAbsDiffExps ),
         ( UInt(10) <= satAbsDiffExps ), (  UInt(9) <= satAbsDiffExps ),
         (  UInt(8) <= satAbsDiffExps ), (  UInt(7) <= satAbsDiffExps ),
         (  UInt(6) <= satAbsDiffExps ), (  UInt(5) <= satAbsDiffExps ),
         (  UInt(4) <= satAbsDiffExps ), (  UInt(3) <= satAbsDiffExps ));
//*** USE `wrapAbsDiffExps' AND MASK RESULT?
    val far_alignedSigSmaller =
        Cat(Cat(sigSmaller, UInt("d0",2))>>satAbsDiffExps,
         ( ( sigSmaller & far_roundExtraMask ) != UInt(0) ));
    val far_negAlignedSigSmaller =
        Mux(eqOpSigns , Cat(UInt("b0",1), far_alignedSigSmaller),
                  Cat(UInt("b1",1), ~ far_alignedSigSmaller));
    val far_sigSumIncr = ~ eqOpSigns;
    val far_sigSum =
        Cat(UInt("b0",1), sigLarger, UInt("d0",3)).toUInt + far_negAlignedSigSmaller.toUInt + far_sigSumIncr.toUInt;
    val far_sumShift1  = far_sigSum(27).toBool;
    val far_sumShift0  = ( far_sigSum(27,26) === UInt("b01",2) );
    val far_sumShiftM1 = ( far_sigSum(27,26) === UInt("b00",2) );
    val far_fractX =
          ( Mux(far_sumShift1, Cat(far_sigSum(26,3), ( far_sigSum(2,0) != UInt(0) )) , UInt(0) )) |
          ( Mux(far_sumShift0, Cat(far_sigSum(25,2), ( far_sigSum(1,0) != UInt(0) )) , UInt(0) )) |
          ( Mux(far_sumShiftM1, far_sigSum(24,0)                            , UInt(0) ));

    val far_roundInexact = ( far_fractX(1,0) != UInt(0) );
    val far_roundIncr =
          ( roundingMode_nearest_even & far_fractX(1)                   ) |
          ( roundingMode_minMag       & UInt(0)                          ) |
          ( roundingMode_min          &   signLarger & far_roundInexact ) |
          ( roundingMode_max          & ~ signLarger & far_roundInexact );
    val far_roundEven =
        roundingMode_nearest_even & ( far_fractX(1,0) === UInt("b10",2) );
    val far_cFractYN = ( far_fractX.toUInt>>UInt(2) ) + far_roundIncr.toUInt;
    val far_roundCarry = far_cFractYN(23).toBool;
//*** COMBINE EXP ADJUST ADDERS FOR CLOSE AND FAR PATHS?
    val far_expAdjust =
          Mux( far_sumShift1 | ( far_sumShift0 & far_roundCarry ) , UInt(1)       , UInt(0) ) |
          ( Mux(far_sumShiftM1 & ~ far_roundCarry, UInt("b111111111",9).toUInt , UInt(0) ));
    val far_expY = expLarger + far_expAdjust;
    val far_fractY =
        Cat(far_cFractYN(22,1), far_cFractYN(0) & ~ far_roundEven);

    /*------------------------------------------------------------------------*/
    /*------------------------------------------------------------------------*/
    val isZeroY = doCloseSubMags & close_isZeroY;
    val signY  = Mux(doCloseSubMags, close_signY  , signLarger);
    val expY   = Mux(doCloseSubMags, close_expY   , far_expY);
    val fractY = Mux(doCloseSubMags, close_fractY , far_fractY);
    val overflowY = ~ doCloseSubMags & ( far_expY(8,7) === UInt("b11",2) );
    val inexactY = Mux(doCloseSubMags, close_roundInexact , far_roundInexact);

    val overflowY_roundMagUp =
        roundingMode_nearest_even | ( roundingMode_min & signLarger ) |
              ( roundingMode_max & ~ signLarger );

    /*------------------------------------------------------------------------*/
    /*------------------------------------------------------------------------*/
    val addSpecial = isSpecialA | isSpecialB;
    val addZeros = isZeroA & isZeroB;
    val commonCase = ~ addSpecial & ~ addZeros;

    val common_invalid = isInfA & isInfB & ~ eqOpSigns;
    val invalid = isSigNaNA | isSigNaNB | common_invalid;
    val overflow = commonCase & overflowY;
    val inexact = overflow | ( commonCase & inexactY );

    val notSpecial_isZeroOut = addZeros | isZeroY;
    val isSatOut = overflow & ~ overflowY_roundMagUp;
    val notNaN_isInfOut =
        isInfA | isInfB | ( overflow & overflowY_roundMagUp );
    val isNaNOut = isNaNA | isNaNB | common_invalid;

    val signOut =
          ( eqOpSigns              & signA   ) |
          ( isNaNA                 & signA   ) | 
          ( ~ isNaNA & isNaNB      & opSignB ) |
          ( isInfA & ~ isSpecialB  & signA   ) |
          ( ~ isSpecialA & isInfB  & opSignB ) |
          ( invalid                & UInt(0)       ) |
          ( addZeros & ~ eqOpSigns & UInt(0)       ) |
          ( commonCase             & signY   );
    val expOut =
        (   expY &
            ~ ( Mux(notSpecial_isZeroOut, UInt("b111000000",9) , UInt(0) )) &
            ~ ( Mux(isSatOut, UInt("b010000000",9) , UInt(0) )) &
            ~ ( Mux(notNaN_isInfOut, UInt("b001000000",9) , UInt(0) )) ) | 
            ( Mux(isSatOut, UInt("b101111111",9) , UInt(0) )) |
            ( Mux(notNaN_isInfOut, UInt("b110000000",9) , UInt(0) )) |
            ( Mux(isNaNOut, UInt("b111000000",9) , UInt(0) ));
    val fractOut = fractY | ( Mux(isNaNOut | isSatOut, UInt("h7FFFFF",23) , UInt(0) ));
    io.out := Cat(signOut, expOut, fractOut);

    io.exceptionFlags := Cat(invalid, UInt("b0",1), overflow, UInt("b0",1), inexact);
}
