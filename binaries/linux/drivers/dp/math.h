/*
 * The vendored xdppsu_spm.c computes an EDID-preferred-timing frame rate with `double`
 * arithmetic and nearbyint() - the only floating point in the vendored code, on a path
 * this module never calls (modes come from the standard timing table, see
 * XDpPsu_CfgMsaUseStandardVideoMode). A kernel has no floating-point runtime to link
 * against, so the types are remapped to integer arithmetic: the uncalled path loses its
 * rounding nicety, and the object file loses the libm/soft-float dependencies that would
 * otherwise make the module unloadable.
 */
#ifndef SOCT_DP_MATH_H
#define SOCT_DP_MATH_H

#define double u64
#define nearbyint(x) (x)

#endif /* SOCT_DP_MATH_H */
