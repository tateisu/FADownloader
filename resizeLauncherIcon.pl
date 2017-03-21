#!perl --
use strict;
use warnings;
use Image::Magick;

sub resize{
	my($src_file,$dst_file,$resize_w,$resize_h) = @_;
	my $image = new Image::Magick;
	$image->read($src_file);
	$image -> Resize(
		width  => $resize_w,
		height => $resize_h,
		blur   => 0.8,
	);
	$image -> Write( "png:$dst_file" );
	print "$dst_file\n";
}

my $res_dir = "app/src/main/res";

{
	my $res_name = "ic_launcher";
	my $src = "ic_launcher-512.png";

	for(
		[qw( mipmap-mdpi 48 )],
		[qw( mipmap-hdpi 72 )],
		[qw( mipmap-xhdpi 96 )],
		[qw( mipmap-xxhdpi 144 )],
		[qw( mipmap-xxxhdpi 192 )],
	){
		my($subdir,$size)=@$_;
		mkdir("$res_dir/$subdir",0777);
		resize( $src, "$res_dir/$subdir/$res_name.png", $size,$size );
	}
}
{
	my $res_name = "ic_service";
	my $src = "ic_service-512.png";

	for(
		[qw( drawable-mdpi 24 )],
		[qw( drawable-hdpi 36 )],
		[qw( drawable-xhdpi 48 )],
		[qw( drawable-xxhdpi 72 )],
		[qw( drawable-xxxhdpi 96 )],
	){
		my($subdir,$size)=@$_;
		mkdir("$res_dir/$subdir",0777);
		resize( $src, "$res_dir/$subdir/$res_name.png", $size,$size );
	}
}
