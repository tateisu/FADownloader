#!perl --
use strict;
use warnings;

use File::Find;
use Data::Dump qw(dump);
use XML::Simple qw(:strict);

binmode(\*STDOUT,':encoding(utf8)');
binmode(\*STDERR,':encoding(utf8)');

my %known_param = map{ ($_,1) } qw( %1$s %2$s %3$s %4$s %1$d );

my %data = map{ ($_,{ files=>[] }) } qw( strings.xml build_variant.xml );

find( sub{
	my $info = $data{ $_ };
	$info and push @{$info->{files}},$File::Find::name;
}, 'app/src' );

while( my($basename,$info) = each %data ){
	my %res;
	for my $file ( @{$info->{files}} ){
		my $xml = XMLin( $file 
			,KeyAttr => { string => 'name'}
			,ForceArray => 'string'
		);
		warn "\n";
		my $string_map = $xml->{string};
		# print dump( $xml );
		while( my($k,$v) = each %$string_map ){
			my $text = $v->{content};
			
			my @params;
			while( $text =~ /(\%[\d\$]*[sdfxc])/gi ){
				my $param = $1;
				push @params,$1;
				if( not $known_param{ $param }){
					print "$file $k : bad parameter $param\n";
				}
			}
			my $params_sign = join(',',sort { $a cmp $b } @params);
			
			my $files = $res{$k};
			$files or $files = $res{$k}=[];
			push @$files,[$file,$text,$params_sign];
		}
	}
	my $file_count = 0 + @{$info->{files}};
	while( my($id,$files) = each %res ){
		if( $file_count != 0+@$files ){
			print "NG: $basename: $id is not decrared in all files.\n";
			for my $file ( sort { $a->[0] cmp $b->[0] } @$files ){
				print "  $file->[0] : $file->[1]\n";
			}
		}else{
			my $error = 0;
			for( my $i=1; $i < @$files;++$i ){
				if( $files->[0][2] ne $files->[$i][2] ){
					$error = 1;
				}
			}
			if( $error ){
				print "NG: $basename $id : parameter not same.\n";
				for my $file ( sort { $a->[0] cmp $b->[0] } @$files ){
					print "  $file->[0] : ($file->[2]) $file->[1]\n";
				}
			}
		}
	}
}

